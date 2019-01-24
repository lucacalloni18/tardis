package exec;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.nio.file.Files;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import concurrent.InputBuffer;
import concurrent.OutputBuffer;
import concurrent.Performer;
import jbse.mem.State;
import sushi.execution.jbse.StateFormatterSushiPathCondition;

public class PerformerEvosuite extends Performer<JBSEResult, EvosuiteResult> {
	private final String classesPath;
	private final Path tmpPath;
	private final Path tmpBinTestsPath;
	private final String evosuitePath;
	private final String sushiLibPath;
	private final String outPath;
	private final long timeBudgetSeconds;
	private final boolean useMOSA;
	private final TestIdentifier testIdentifier;
	private final JavaCompiler compiler;
	
	public PerformerEvosuite(Options o, InputBuffer<JBSEResult> in, OutputBuffer<EvosuiteResult> out) throws IOException {
		super(in, out, o.getNumOfThreads(), (o.getUseMOSA() ? o.getNumMOSATargets() : 1), o.getTimeoutMOSATaskCreationDuration(), o.getTimeoutMOSATaskCreationUnit());
		this.classesPath = String.join(File.pathSeparator, stream(o.getClassesPath()).map(Object::toString).toArray(String[]::new)); 
		this.tmpPath = o.getTmpDirectoryPath();
		this.tmpBinTestsPath = o.getTmpBinTestsDirectoryPath();
		this.evosuitePath = o.getEvosuitePath().toString();
		this.sushiLibPath = o.getSushiLibPath().toString();
		this.outPath = o.getOutDirectory().toString();
		this.timeBudgetSeconds = o.getEvosuiteTimeBudgetUnit().toSeconds(o.getEvosuiteTimeBudgetDuration());
		this.useMOSA = o.getUseMOSA();
		this.testIdentifier = new TestIdentifier(o.getInitialTestCase() == null ? 0 : 1);
		this.compiler = ToolProvider.getSystemJavaCompiler();

		if (this.compiler == null) {
			Main.printConsole("[EVOSUITE] Failed to find a system Java compiler. Did you install a JDK?");
			//TODO throw an exception
		}
	}
	
    /**
     * Converts an iterable to a stream.
     * See <a href="https://stackoverflow.com/a/23177907/450589">https://stackoverflow.com/a/23177907/450589</a>.
     * @param it an {@link Iterable}{@code <T>}.
     * @return a {@link Stream}{@code <T>} for {@code it}.
     */
    private static <T> Stream<T> stream(Iterable<T> it) {
        return StreamSupport.stream(it.spliterator(), false);
    }

	@Override
	protected Runnable makeJob(List<JBSEResult> items) {
		final int testCountInitial = this.testIdentifier.getTestCount();
		this.testIdentifier.testCountAdd(items.size());
		final Runnable job = () -> generateTestsAndScheduleJBSE(testCountInitial, items);
		return job;
	}
	
	/**
	 * Invokes EvoSuite to generate a set of {@link TestCase}s that cover a 
	 * set of path condition, and then explores the generated test cases 
	 * starting from the depth of the respective path conditions.
	 * 
	 * @param testCountInitial an {@code int}, the number used to identify 
	 *        the generated tests. The test generated from {@code items.get(i)}
	 *        will be numbered {@code testCountInitial + i}.
	 * @param items a {@link List}{@code <}{@link JBSEResult}{@code >}, results of symbolic execution.
	 * @throws IOException 
	 */
	private void generateTestsAndScheduleJBSE(int testCountInitial, List<JBSEResult> items) {
		if (!this.useMOSA && items.size() != 1) {
			System.out.println("[EVOSUITE] Unexpected internal error: MOSA is not used but the number of targets passed to EvoSuite is different from 1");
			return; //TODO throw an exception?
		}
		
		//splits items in sublists having same target method
		final Map<String, List<JBSEResult>> splitItems = 
				items.stream().collect(Collectors.groupingBy(r -> r.getTargetClassName() + ":" + r.getTargetMethodDescriptor() + ":" + r.getTargetMethodName()));

		//launches an EvoSuite process for each sublist
		final ArrayList<Thread> threads = new ArrayList<>();
		int testCountStart = testCountInitial;
		for (List<JBSEResult> subItems : splitItems.values()) {
			final int testCount = testCountStart; //copy into final variable to keep compiler happy
			testCountStart += subItems.size(); //for the next iteration
			
			//generates and compiles the wrappers
			emitAndCompileEvoSuiteWrappers(testCount, subItems);
			
			//builds the EvoSuite command line
			final List<String> evosuiteCommand = buildEvoSuiteCommand(testCount, subItems); 

			//launches EvoSuite

			final Path evosuiteLogFilePath = this.tmpPath.resolve("evosuite-log-" + testCount + ".txt");
			final Process processEvosuite;
			try {
				processEvosuite = launchProcess(evosuiteCommand, evosuiteLogFilePath);
			} catch (IOException e) {
				System.out.println("[EVOSUITE] Unexpected I/O error while running EvoSuite: " + e);
				return; //TODO throw an exception?
			}

			//launches a thread that waits for tests and schedules 
			//JBSE for exploring them
			final TestDetector tdJBSE = new TestDetector(testCount, subItems, evosuiteLogFilePath);
			final Thread tJBSE = new Thread(tdJBSE);
			tJBSE.start();
			threads.add(tJBSE);
			
			//launches another thread that waits for EvoSuite to end
			//and then alerts the previous thread
			final Thread tEvosuiteEnd = new Thread(() -> {
				try {
					processEvosuite.waitFor();
				} catch (InterruptedException e) {
					//the performer was shut down: kill the EvoSuite job
					processEvosuite.destroy();
				}
				tdJBSE.ended = true;
			});
			tEvosuiteEnd.start();
			threads.add(tEvosuiteEnd);
		}
		
		//waits for all the threads to end (if it didn't the performer
		//would consider the job over and would incorrectly detect whether 
		//it is idle)
		boolean interrupted = false;
		for (Thread thread : threads) {
			try {
				if (interrupted) {
					thread.interrupt();
				} else {
					thread.join();
				}
			} catch (InterruptedException e) {
				interrupted = true;
				thread.interrupt();
			}
		}
	}

	/**
	 * Emits the EvoSuite wrapper (file .java) for the path condition of some state.
	 * 
	 * @param testCount an {@code int}, the number used to identify the test.
	 * @param initialState a {@link State}; must be the initial state in the execution 
	 *        for which we want to generate the wrapper.
	 * @param finalState a {@link State}; must be the final state in the execution 
	 *        for which we want to generate the wrapper.
	 * @return a {@link Path}, the file path of the generated EvoSuite wrapper.
	 */
	private Path emitEvoSuiteWrapper(int testCount, State initialState, State finalState) {
		final StateFormatterSushiPathCondition fmt = new StateFormatterSushiPathCondition(testCount, () -> initialState);
		fmt.formatPrologue();
		fmt.formatState(finalState);
		fmt.formatEpilogue();

		final Path wrapperFilePath = this.tmpPath.resolve("EvoSuiteWrapper_" + testCount + ".java");
		try (final BufferedWriter w = Files.newBufferedWriter(wrapperFilePath)) {
			w.write(fmt.emit());
		} catch (IOException e) {
			System.out.println("[EVOSUITE] Unexpected I/O error while creating EvoSuite wrapper " + wrapperFilePath.toString() + ": " + e);
			//TODO throw an exception
		}
		fmt.cleanup();

		return wrapperFilePath;
	}
	
	/**
	 * Emits and compiles all the EvoSuite wrappers.
	 * 
	 * @param testCountInitial an {@code int}, the number used to identify 
	 *        the generated tests. The test generated from {@code items.get(i)}
	 *        will be numbered {@code testCountInitial + i}.
	 * @param items a {@link List}{@code <}{@link JBSEResult}{@code >}, results of symbolic execution.
	 */
	private void emitAndCompileEvoSuiteWrappers(int testCountInitial, List<JBSEResult> items) {
		final String classpathCompilationWrapper = this.classesPath + File.pathSeparator + this.sushiLibPath;
		int i = testCountInitial;
		for (JBSEResult item : items) {
			final State initialState = item.getInitialState();
	//	System.out.println(initialState);
			final State finalState = item.getFinalState();
			//aggiunta stampe
	//		System.out.println(initialState);
			
			//System.out.println("initialState:"+ item.getInitialState());
			//System.out.println("finalState:"+ item.getFinalState());
			final Path wrapperFilePath = emitEvoSuiteWrapper(i, initialState, finalState);
			final Path javacLogFilePath = this.tmpPath.resolve("javac-log-" + i + ".txt");
			final String[] javacParameters = { "-cp", classpathCompilationWrapper, "-d", this.tmpPath.toString(), wrapperFilePath.toString() };
			try (final OutputStream w = new BufferedOutputStream(Files.newOutputStream(javacLogFilePath))) {
				this.compiler.run(null, w, w, javacParameters);
			} catch (IOException e) {
				System.out.println("[EVOSUITE] Unexpected I/O error while creating wrapper compilation log file " + javacLogFilePath.toString() + ": " + e);
				//TODO throw an exception
			}
			++i;
		}
	}

	/**
	 * Builds the command line for invoking EvoSuite.
	 * 
	 * @param testCountInitial an {@code int}, the number used to identify 
	 *        the generated tests. The test generated from {@code items.get(i)}
	 *        will be numbered {@code testCountInitial + i}.
	 * @param items a {@link List}{@code <}{@link JBSEResult}{@code >}, results of symbolic execution.
	 *        All the items in {@code items} must refer to the same target method, i.e., must have same
	 *        {@link JBSEResult#getTargetClassName() class name}, {@link JBSEResult#getTargetMethodDescriptor() method descriptor}, and 
	 *        {@link JBSEResult#getTargetMethodName() method name}.
	 * @return a command line in the format of a {@link List}{@code <}{@link String}{@code >},
	 *         suitable to be passed to a {@link ProcessBuilder}.
	 */
	private List<String> buildEvoSuiteCommand(int testCountInitial, List<JBSEResult> items) {
		final String targetClass = items.get(0).getTargetClassName();
		final String targetMethodDescriptor = items.get(0).getTargetMethodDescriptor();
		final String targetMethodName = items.get(0).getTargetMethodName();
		final String classpathEvosuite = this.classesPath + File.pathSeparator + this.sushiLibPath + File.pathSeparator + this.tmpPath;
		final List<String> retVal = new ArrayList<String>();
		retVal.add("java");
		//retVal.add("-Xmx4G");
		retVal.add("-Xmx30G");
		retVal.add("-jar");
		retVal.add(this.evosuitePath);
		retVal.add("-class");
		retVal.add(targetClass.replace('/', '.'));
		retVal.add("-mem");
		retVal.add("2048");
		retVal.add("-DCP=" + classpathEvosuite); 
		retVal.add("-Dassertions=false");
		retVal.add("-Dglobal_timeout=" + this.timeBudgetSeconds);
		retVal.add("-Dreport_dir=" + this.tmpPath);
		retVal.add("-Dsearch_budget=" + this.timeBudgetSeconds);
		retVal.add("-Dtest_dir=" + this.outPath);
		retVal.add("-Dvirtual_fs=false");
		retVal.add("-Dselection_function=ROULETTEWHEEL");
		retVal.add("-Dcatch_undeclared_exceptions=false");
		retVal.add("-Dcriterion=PATHCONDITION");		
		retVal.add("-Dsushi_statistics=true");
		retVal.add("-Dinline=false");
		retVal.add("-Dsushi_modifiers_local_search=true");
		retVal.add("-Dpath_condition_target=LAST_ONLY");
		retVal.add("-Duse_minimizer_during_crossover=true");
		retVal.add("-Davoid_replicas_of_individuals=true"); 
		retVal.add("-Dno_change_iterations_before_reset=30");
		if (this.useMOSA) {
			retVal.add("-Demit_tests_incrementally=true");
			retVal.add("-Dcrossover_function=SUSHI_HYBRID");
			retVal.add("-Dalgorithm=DYNAMOSA");
			retVal.add("-generateMOSuite");
		} else {
			retVal.add("-Djunit_suffix=" + "_" + testCountInitial  + "_Test");
			retVal.add("-Dhtml=false");
			retVal.add("-Dcrossover_function=SINGLEPOINT");
			retVal.add("-Dcrossover_implementation=SUSHI_HYBRID");
			retVal.add("-Dmax_size=1");
			retVal.add("-Dmax_initial_tests=1");
		}
		final StringBuilder optionPC = new StringBuilder("-Dpath_condition=");
		for (int i = testCountInitial; i < testCountInitial + items.size(); ++i) {
			if (i > testCountInitial) {
				optionPC.append(":");
			}
			optionPC.append(targetClass.replace('/', '.') + "," + targetMethodName + targetMethodDescriptor + ",EvoSuiteWrapper_" + i);
		}
		retVal.add(optionPC.toString());
		
		return retVal;
	}
	
	/**
	 * Creates and launches an external process.
	 * 
	 * @param commandLine a {@link List}{@code <}{@link String}{@code >}, the command line
	 *        to launch the process in the format expected by {@link ProcessBuilder}.
	 * @param logFilePath a {@link Path} to a log file where stdout and stderr of the
	 *        process will be redirected.
	 * @return the created {@link Process}.
	 * @throws IOException if thrown by {@link ProcessBuilder#start()}.
	 */
	private Process launchProcess(List<String> commandLine, Path logFilePath) throws IOException {
		final ProcessBuilder pb = new ProcessBuilder(commandLine).redirectErrorStream(true).redirectOutput(logFilePath.toFile());
		final Process pr = pb.start();
		return pr;
	}

	/**
	 * Class for a {@link Runnable} that listens for the output produced by 
	 * an instance of EvoSuite, and when this produces a test
	 * schedules JBSE for its analysis.
	 * 
	 * @author Pietro Braione
	 */
	private final class TestDetector implements Runnable {
		private final int testCountInitial;
		private final List<JBSEResult> items;
		private final Path evosuiteLogFilePath;
		public volatile boolean ended;
		
		/**
		 * Constructor.
		 * 
		 * @param testCountInitial an {@code int}, the number used to identify 
		 *        the generated tests. The test generated from {@code items.get(i)}
		 *        will be numbered {@code testCountInitial + i}.
		 * @param items a {@link List}{@code <}{@link JBSEResult}{@code >}, results of symbolic execution.
		 * @param evosuiteLogFilePath the {@link Path} of the EvoSuite log file.
		 */
		public TestDetector(int testCountInitial, List<JBSEResult> items, Path evosuiteLogFilePath) {
			this.testCountInitial = testCountInitial;
			this.items = items;
			this.evosuiteLogFilePath = evosuiteLogFilePath;
			this.ended = false;
		}
		
		@Override
		public void run() {
			try {
				detectTestsAndScheduleJBSE();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		/**
		 * Waits for EvoSuite to emit test classes and schedules JBSE
		 * for their further analysis.
		 * @throws IOException 
		 */
		private void detectTestsAndScheduleJBSE() throws IOException {
			final Pattern patternEmittedTest = Pattern.compile("^.*\\* EMITTED TEST CASE: EvoSuiteWrapper_(\\d+), \\w+\\z");
			final HashSet<Integer> generated = new HashSet<>();
			try (final BufferedReader r = Files.newBufferedReader(this.evosuiteLogFilePath)) {
				//modified from https://stackoverflow.com/a/154588/450589
				while (true) {
					final String line = r.readLine();
					if (line == null) { 
						//no lines in the file
						if (this.ended) {
							break;
						} else {
							//possibly more lines in the future: wait a little bit
							//and retry
							Thread.sleep(2000);
						}
					} else {
						//check if the read line reports the emission of a test case
						//and in the positive case schedule JBSE to analyze it
						final Matcher matcherEmittedTest = patternEmittedTest.matcher(line);
						if (matcherEmittedTest.matches()) {
							final int testCount = Integer.parseInt(matcherEmittedTest.group(1));
							generated.add(testCount);
							final JBSEResult item = this.items.get(testCount - this.testCountInitial);
							checkTestCompileAndScheduleJBSE(testCount, item);
						}
					}
				}
			} catch (InterruptedException e) {
				//the performer was shut down:
				//just fall through
			} catch (IOException e) {
				Main.printConsole("[EVOSUITE] Unexpected I/O error while reading EvoSuite log file " + this.evosuiteLogFilePath.toString() + ": " + e);
				//TODO throw an exception?
			}
			
			//ended reading EvoSuite log file: warns about tests that 
			//have not been generated and exits
			int testCount = this.testCountInitial;
			for (JBSEResult item : this.items) {
				if (!generated.contains(testCount)) {
					Main.printConsole("[EVOSUITE] Failed to generate a test case for PC: " + item.getFinalState().getPathCondition() + ", log file: " + this.evosuiteLogFilePath.toString() + ", wrapper: EvoSuiteWrapper_" + testCount);
				}
				++testCount;
			}
		}
		
		/**
		 * Checks that an emitted test class has the {@code test0} method,
		 * to filter out the cases where EvoSuite fails but emits the test class.
		 * 
		 * @param className a {@link String}, the name of the test class.
		 * @throws NoSuchMethodException if the class {@code className} has not
		 *         a {@code void test0()} method.
		 * @throws IOException 
		 */
		private void checkTestExists(String className) throws NoSuchMethodException, IOException {
			try {
				final URLClassLoader cloader = URLClassLoader.newInstance(new URL[]{ PerformerEvosuite.this.tmpBinTestsPath.toUri().toURL() }); 
				cloader.loadClass(className.replace('/',  '.')).getDeclaredMethod("test0");
			} catch (SecurityException | ClassNotFoundException | MalformedURLException e) {
				Main.printConsole("[EVOSUITE] Unexpected error while verifying that class " + className + " exists and has a test method: " + e);
				//TODO throw an exception
			} 			
		}
		
		/**
		 * Checks whether EvoSuite emitted a well-formed test class, and in the
		 * positive case compiles the generated test and schedules JBSE for its
		 * exploration.
		 *  
		 * @param testCount an {@code int}, the number that identifies 
		 *        the generated test.
		 * @param item a {@link JBSEResult}, the result of the symbolic execution
		 *        from which the test was generated.
		 * @throws IOException 
		 */
		private void checkTestCompileAndScheduleJBSE(int testCount, JBSEResult item) throws IOException {
			final jbse.mem.State finalState = item.getFinalState();
			final int depth = item.getDepth();
			
			//checks if EvoSuite generated the files
			final String testCaseClassName = item.getTargetClassName() + "_" + testCount + "_Test";
			final String testCaseScaff = PerformerEvosuite.this.outPath + "/" + testCaseClassName + "_scaffolding.java";
			final String testCase = PerformerEvosuite.this.outPath + "/" + testCaseClassName + ".java";
			if (!new File(testCase).exists() || !new File(testCaseScaff).exists()) {
				Main.printConsole("[EVOSUITE] Failed to generate the test case " + testCaseClassName + " for PC: " + finalState.getPathCondition() + ": the generated files do not seem to exist");
				return;
			}
			
			//compiles the generated test
			final String classpathCompilationTest = PerformerEvosuite.this.tmpBinTestsPath.toString() + File.pathSeparator + PerformerEvosuite.this.classesPath + File.pathSeparator + PerformerEvosuite.this.sushiLibPath + File.pathSeparator + PerformerEvosuite.this.evosuitePath;
			final Path javacLogFilePath = PerformerEvosuite.this.tmpPath.resolve("javac-log-test-" +  testCount + ".txt");
			final String[] javacParametersTestScaff = { "-cp", classpathCompilationTest, "-d", PerformerEvosuite.this.tmpBinTestsPath.toString(), testCaseScaff };
			final String[] javacParametersTestCase = { "-cp", classpathCompilationTest, "-d", PerformerEvosuite.this.tmpBinTestsPath.toString(), testCase };
			try (final OutputStream w = new BufferedOutputStream(Files.newOutputStream(javacLogFilePath))) {
				PerformerEvosuite.this.compiler.run(null, w, w, javacParametersTestScaff);
				PerformerEvosuite.this.compiler.run(null, w, w, javacParametersTestCase);
			} catch (IOException e) {
				Main.printConsole("[EVOSUITE] Unexpected I/O error while creating test case compilation log file " + javacLogFilePath.toString() + ": " + e);
				//TODO throw an exception
			}
			
			//creates the TestCase and schedules it for further exploration
			try {
				checkTestExists(testCaseClassName);
				Main.printConsole("[EVOSUITE] Generated test case " + testCaseClassName + ", depth: " + depth + ", path condition: " + finalState.getPathCondition());
				final TestCase newTC = new TestCase(testCaseClassName, "()V", "test0");
				PerformerEvosuite.this.getOutputBuffer().add(new EvosuiteResult(item, newTC, depth + 1));
			} catch (NoSuchMethodException e) { 
				//EvoSuite failed to generate the test case, thus we just ignore it 
				Main.printConsole("[EVOSUITE] Failed to generate the test case " + testCaseClassName + " for PC: " + finalState.getPathCondition() + ": the generated file does not contain a test method");
			}
		}
	}
}

package exec;

import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.exists;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.ParserProperties;

import concurrent.TerminationManager;
import jbse.bc.ClassFileFactoryJavassist;
import jbse.bc.Classpath;
import jbse.bc.Signature;
import jbse.bc.exc.BadClassFileException;
import jbse.bc.exc.InvalidClassFileFactoryClassException;
import jbse.bc.exc.MethodCodeNotFoundException;
import jbse.bc.exc.MethodNotFoundException;
import jbse.mem.State;
import jbse.rewr.CalculatorRewriting;
import jbse.rewr.RewriterOperationOnSimplex;
import sushi.util.ClassReflectionUtils;

public final class Main {
	private final Options o;
	
	/* MAME */
	private static Path dirLogsPathConditions;
	/* MAME */
	
	public Main(Options o) {
		this.o = o;
		
		/* MAME */
		Main.dirLogsPathConditions = o.getLogsPathConditionsDirectoryPath();
		/* MAME */
	}
	
	/* MAME */
	public static void printConsole(String text) throws IOException {
		System.out.println(text);
		File log = new File(dirLogsPathConditions + "/console.txt");
		if (!log.exists()) {
			log.createNewFile();
		}
		PrintWriter out = new PrintWriter(new FileWriter(log, true));
		BufferedWriter bw = new BufferedWriter(out);
		bw.write(text + "\n");
		bw.close();			
	}
	/* MAME */
	
	public void start() throws IOException {
		//creates the temporary directories if it does not exist
		if (!exists(o.getTmpDirectoryPath())) {
			createDirectory(o.getTmpDirectoryPath());
		}
		if (!exists(o.getTmpBinTestsDirectoryPath())) {
			createDirectory(o.getTmpBinTestsDirectoryPath());
		}
		
		/* MAME */
		if (!exists(o.getLogsPathConditionsDirectoryPath())) {
			createDirectory(o.getLogsPathConditionsDirectoryPath());
		}
		/* MAME */
		
		//creates the coverage data structure
		final CoverageSet coverageSet = new CoverageSet();
		
		//creates the communication queues between the performers
		final QueueInputOutputBuffer<JBSEResult> pathConditionBuffer = new QueueInputOutputBuffer<>();
		final QueueInputOutputBuffer<EvosuiteResult> testCaseBuffer = new QueueInputOutputBuffer<>();
		
		//creates and wires together the components of the architecture
		final PerformerJBSE performerJBSE = new PerformerJBSE(this.o, testCaseBuffer, pathConditionBuffer, coverageSet);
		final PerformerEvosuite performerEvosuite = new PerformerEvosuite(this.o, pathConditionBuffer, testCaseBuffer);
		final TerminationManager terminationManager = new TerminationManager(this.o.getGlobalTimeBudgetDuration(), this.o.getGlobalTimeBudgetUnit(), performerJBSE, performerEvosuite);
		
		//seeds the initial test cases
		if (this.o.getTargetMethod() == null || this.o.getInitialTestCase() == null) {
			//the target is a whole class, or is a single method but
			//there is no initial test case: EvoSuite should start
			final ArrayList<JBSEResult> seed = seedForEvosuite();
			performerEvosuite.seed(seed);
		} else {
			//the target is a single method and there is one
			//initial test case: JBSE should start
			final ArrayList<EvosuiteResult> seed = seedForJBSE();
			performerJBSE.seed(seed);
		}
		
		//starts everything
		
		final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
		
		/* MAME */
		printConsole("[MAIN    ] Starting at " + dtf.format(LocalDateTime.now()));
		/* MAME */		
		
		performerJBSE.start();
		performerEvosuite.start();
		terminationManager.start();
		
		//waits end and prints a final message
		terminationManager.waitTermination();
		
		/* MAME */
		printConsole("[MAIN    ] Ending at " + dtf.format(LocalDateTime.now()));
		/* MAME */
	
	}
	
	private ArrayList<JBSEResult> seedForEvosuite() {
		//this is the "no initial test case" situation
		try {
			final String[] classpath = new String[this.o.getClassesPath().size() + 2];
			classpath[0] = this.o.getJREPath().toString();
			classpath[1] = this.o.getJBSELibraryPath().toString();
			for (int i = 2; i < classpath.length; ++i) {
				classpath[i] = this.o.getClassesPath().get(i - 2).toString();
			}
			final CalculatorRewriting calc = new CalculatorRewriting();
			calc.addRewriter(new RewriterOperationOnSimplex());
			final ArrayList<JBSEResult> retVal = new ArrayList<>();
			if (this.o.getTargetMethod() == null) {
				//this.o indicates a target class
				final List<List<String>> targetMethods = ClassReflectionUtils.getVisibleMethods(this.o.getTargetClass(), true);
				for (List<String> targetMethod : targetMethods) {
					final State s = new State(new Classpath(classpath), ClassFileFactoryJavassist.class, new HashMap<>(), calc);
					s.pushFrameSymbolic(new Signature(targetMethod.get(0), targetMethod.get(1), targetMethod.get(2)));
					retVal.add(new JBSEResult(targetMethod.get(0), targetMethod.get(1), targetMethod.get(2), s, s, s, false, -1));
				}
			} else {
				//this.o indicates a single target method
				final State s = new State(new Classpath(classpath), ClassFileFactoryJavassist.class, new HashMap<>(), calc);
				s.pushFrameSymbolic(new Signature(this.o.getTargetMethod().get(0), this.o.getTargetMethod().get(1), this.o.getTargetMethod().get(2)));
				retVal.add(new JBSEResult(this.o.getTargetMethod().get(0), this.o.getTargetMethod().get(1), this.o.getTargetMethod().get(2), s, s, s, false, -1));
			}
			return retVal;
		} catch (BadClassFileException | ClassNotFoundException | MethodNotFoundException | MethodCodeNotFoundException e) {
			System.out.println("[MAIN    ] Error: The target class or target method does not exist, or the target method is abstract");
			System.exit(1);
		} catch (InvalidClassFileFactoryClassException e) {
			System.out.println("[MAIN    ] Unexpected internal error: Wrong class file factory");
			System.exit(2);
		}
		return null; //to keep the compiler happy
	}
	
	private ArrayList<EvosuiteResult> seedForJBSE() {
		final TestCase tc = new TestCase(this.o);
		final ArrayList<EvosuiteResult> retVal = new ArrayList<>();
		retVal.add(new EvosuiteResult(this.o.getTargetMethod().get(0), this.o.getTargetMethod().get(1), this.o.getTargetMethod().get(2), tc, 0));
		return retVal;
	}
	
	//Here starts the static part of the class, for managing the command line
	
	public static void main(String[] args) throws IOException {		
		//parses options from the command line and exits if the command line
		//is ill-formed
		final Options o = new Options();
		final CmdLineParser parser = new CmdLineParser(o, ParserProperties.defaults().withUsageWidth(200));
		try {
			parser.parseArgument(processArgs(args));
		} catch (CmdLineException e) {
			System.err.println("Error: " + e.getMessage());
			printUsage(parser);
			System.exit(1);
		}
		
		//prints help and exits if asked to
		if (o.getHelp()) {
			printUsage(parser);
			System.exit(0);
		}

		//runs
		final Main m = new Main(o);
		m.start();
	}

	private static String[] processArgs(final String[] args) {
		final Pattern argPattern = Pattern.compile("(-[a-zA-Z_-]+)=(.*)");
		final Pattern quotesPattern = Pattern.compile("^['\"](.*)['\"]$");
		final List<String> processedArgs = new ArrayList<String>();

		for (String arg : args) {
			final Matcher matcher = argPattern.matcher(arg);
			if (matcher.matches()) {
				processedArgs.add(matcher.group(1));
				final String value = matcher.group(2);
				final Matcher quotesMatcher = quotesPattern.matcher(value);
				if (quotesMatcher.matches()) {
					processedArgs.add(quotesMatcher.group(1));
				} else {
					processedArgs.add(value);
				}
			} else {
				processedArgs.add(arg);
			}
		}

		return processedArgs.toArray(new String[0]);
	}

	private static void printUsage(final CmdLineParser parser) {
		System.err.println("Usage: java " + Main.class.getName() + " <options>");
		System.err.println("where <options> are:");
		// print the list of available options
		parser.printUsage(System.err);
	}
}
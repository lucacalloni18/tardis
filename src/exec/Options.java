package exec;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jbse.bc.Signature;

import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.MapOptionHandler;
import org.kohsuke.args4j.spi.MultiPathOptionHandler;
import org.kohsuke.args4j.spi.PathOptionHandler;

import sushi.configure.SignatureHandler;

public class Options implements Cloneable {
	@Option(name = "-help",
			usage = "Prints usage and exits")
	private boolean help = false;

	@Option(name = "-initial_test",
			usage = "Java signature of the initial test case method for seeding concolic exploration",
			handler = SignatureHandler.class)
	private List<String> initialTestCaseSignature;
	
	@Option(name = "-target_class",
			usage = "Name of the target method (containing the methods to test)")
	private String targetClassName;
	
	@Option(name = "-target_method",
			usage = "Java signature of the target method (the method to test)",
			handler = SignatureHandler.class)
	private List<String> targetMethodSignature;
	
	@Option(name = "-max_depth",
			usage = "The maximum depth at which generation of tests is performed")
	private int maxDepth;
	
	@Option(name = "-num_threads",
			usage = "The number of threads in the thread pool")
	private int numOfThreads;
	
	@Option(name = "-classes",
			usage = "The classpath of the project to analyze",
			handler = MultiPathOptionHandler.class)
	private List<Path> classesPath = Collections.singletonList(Paths.get(".", "bin"));
	
	@Option(name = "-tmp_base",
			usage = "Base directory where the temporary subdirectory is found or created",
			handler = PathOptionHandler.class)
	private Path tmpDirBase = Paths.get(".", "tmp");

	/* MAME */
	@Option(name = "-logs-path-conditions_base",
			usage = "Logs path conditions directory where log of path conditions is created",
			handler = PathOptionHandler.class)
	private Path dirLogsPathConditions = Paths.get(".", "logs-path-conditions");
	/* MAME */
	
	@Option(name = "-tmp_name",
			usage = "Name of the temporary subdirectory to use or create")
	private String tmpDirName = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
	
	@Option(name = "-out",
			usage = "Output directory where the java source files of the created test suite must be put",
			handler = PathOptionHandler.class)
	private Path outDir = Paths.get(".", "out");
	
	@Option(name = "-z3",
			usage = "Path to Z3 binary (default: none, expect Z3 on the system PATH)",
			handler = PathOptionHandler.class)
	private Path z3Path;
	
	@Option(name = "-java8_home",
			usage = "Path to Java 8 home (default: none, expect Java executables on the system PATH)",
			handler = PathOptionHandler.class)
	private Path java8Path;
	
	@Option(name = "-jbse_lib",
			usage = "Path to JBSE library",
			handler = PathOptionHandler.class)
	private Path jbsePath = Paths.get(".", "lib", "jbse.jar");
	
	/* MAME */
	private Path jbsefilesPath;
	/* MAME */

	@Option(name = "-jbse_jre",
			usage = "Path to JRE library suitable for JBSE analysis",
			handler = PathOptionHandler.class)
	private Path jrePath = Paths.get(".", "data", "jre", "rt.jar");
	
	@Option(name = "-evosuite",
			usage = "Path to Evosuite or MOSA",
			handler = PathOptionHandler.class)
	private Path evosuitePath = Paths.get(".", "lib", "evosuite.jar");
	
	@Option(name = "-sushi_lib",
			usage = "Path to Sushi library",
			handler = PathOptionHandler.class)
	private Path sushiPath = Paths.get(".", "lib", "sushi-lib.jar");
	
	@Option(name = "-evosuite_time_budget_duration",
			usage = "Duration of the time budget for EvoSuite")
	private int evosuiteTimeBudgetDuration = 900;
	
	@Option(name = "-evosuite_time_budget_unit",
			usage = "Unit of the time budget for EvoSuite: NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS")
	private TimeUnit evosuiteTimeBudgetUnit = TimeUnit.SECONDS;
	
	@Option(name = "-global_time_budget_duration",
			usage = "Duration of the global time budget")
	private long globalTimeBudgetDuration = 10;
	
	@Option(name = "-global_time_budget_unit",
			usage = "Unit of the global time budget: NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS")
	private TimeUnit globalTimeBudgetUnit = TimeUnit.MINUTES;
	
	@Option(name = "-timeout_mosa_task_creation_duration",
			usage = "Duration of the timeout after which a MOSA job is created")
	private long timeoutMOSATaskCreationDuration = 5;
	
	@Option(name = "-timeout_mosa_task_creation_unit",
			usage = "Unit of the timeout after which a MOSA job is created: NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS")
	private TimeUnit timeoutMOSATaskCreationUnit = TimeUnit.SECONDS;

	@Option(name = "-num_mosa_targets",
			usage = "Maximum number of target passed to a MOSA job")
	private int numMOSATargets = 1;
	
	@Option(name = "-use_mosa",
			usage = "Set to true if you want to use MOSA, false for ordinary EvoSuite")
	private boolean useMOSA = false;
	
	/* MAME */
	@Option(name = "-depth_scope",
			usage = "JBSE depth scope, 0 means unlimited")
	private int depthScope = 0;
	/* MAME */
	
	@Option(name = "-heap_scope",
			usage = "JBSE heap scope in the form <className1>=<maxNumInstances1>; multiple heap scopes can be specified",
			handler = MapOptionHandler.class)
	private Map<String, Integer> heapScope;
	
	/* MAME */
	@Option(name = "-uninterpreted",
			usage = "JBSE uninterpreted method in the form <signature>=<uninterpreted_method>; multiple uninterpreted methods can be specified",
			handler = MapOptionHandler.class)
	private Map<Signature, String> uninterpreted;
	/* MAME */
	
	@Option(name = "-count_scope",
			usage = "JBSE count scope, 0 means unlimited")
	private int countScope = 0;

	public boolean getHelp() {
		return this.help;
	}
	
	public void setHelp(boolean help) {
		this.help = help;
	}
		
	public List<String> getInitialTestCase() {
		return (this.initialTestCaseSignature == null ? null : Collections.unmodifiableList(this.initialTestCaseSignature));
	}
	
	public void setInitialTestCase(String... signature) {
		if (signature.length != 3) {
			return;
		}
		this.initialTestCaseSignature = Arrays.asList(signature.clone());
	}
	
	public void setInitialTestCaseNone() {
		this.initialTestCaseSignature = null;
	}
	
	public String getTargetClass() {
		return this.targetClassName;
	}
	
	public void setTargetClass(String targetClassName) {
		this.targetClassName = targetClassName;
		this.targetMethodSignature = null;
	}
	
	public List<String> getTargetMethod() {
		return (this.targetMethodSignature == null ? null : Collections.unmodifiableList(this.targetMethodSignature));
	}
	
	public void setTargetMethod(String... signature) {
		if (signature.length != 3) {
			return;
		}
		this.targetMethodSignature = Arrays.asList(signature.clone());
		this.targetClassName = null;
	}
	
	public int getMaxDepth() {
		return this.maxDepth;
	}
	
	public void setMaxDepth(int maxdepth) {
		this.maxDepth = maxdepth;
	}
	
	public int getNumOfThreads() {
		return this.numOfThreads;
	}
	
	public void setNumOfThreads(int numOfThreads) {
		this.numOfThreads = numOfThreads;
	}
	
	public List<Path> getClassesPath() {
		return this.classesPath;
	}
	
	public void setClassesPath(Path... paths) {
		this.classesPath = Arrays.asList(paths);
	}
	
	public void setClassesPath(String... strings) {
		for(int i = 0; i < strings.length; i++) {
			this.classesPath = Arrays.asList(Paths.get(strings[i]));
		}
	}
	
	public Path getTmpDirectoryBase() {
		return this.tmpDirBase;
	}
	
	public void setTmpDirectoryBase(Path base) {
		this.tmpDirBase = base;
	}
	
	public void setTmpDirectoryBase(String base) {
		this.tmpDirBase = Paths.get(base);
	}
	
	public String getTmpDirectoryName() {
		return this.tmpDirName;
	}
	
	public void setTmpDirectoryName(String name) {
		this.tmpDirName = name;
	}
	
	public Path getTmpDirectoryPath() {
		if (this.tmpDirName == null) {
			return this.tmpDirBase;
		} else {
			return this.tmpDirBase.resolve(this.tmpDirName);
		}
	}
	
	/* MAME */
	public Path getLogsPathConditionsDirectoryPath() {
		if (this.tmpDirName == null) {
			return this.dirLogsPathConditions;
		} else {
			return this.dirLogsPathConditions.resolve(this.tmpDirName);
		}
	}
	
	public void setLogsPathConditionsDirectoryPath(Path dirLogsPathConditions) {
		this.dirLogsPathConditions = dirLogsPathConditions;
	}
	
	public void setLogsPathConditionsDirectoryPath(String dirLogsPathConditions) {
		this.dirLogsPathConditions = Paths.get(dirLogsPathConditions);
	}
	/* MAME */
	
	public Path getTmpBinTestsDirectoryPath() {
		return getTmpDirectoryPath().resolve("bin");
	}
	
	public Path getOutDirectory() {
		return this.outDir;
	}
	
	public void setOutDirectory(Path dir) {
		this.outDir = dir;
	}
	
	public void setOutDirectory(String dir) {
		this.outDir = Paths.get(dir);
	}
	
	public Path getZ3Path() {
		return this.z3Path;
	}
	
	public void setZ3Path(Path z3Path) {
		this.z3Path = z3Path;
	}
	
	public void setZ3Path(String z3Path) {
		this.z3Path = Paths.get(z3Path);
	}
	
	public Path getJava8Path() {
		return this.java8Path;
	}
	
	public void setJava8Path(Path java8Path) {
		this.java8Path = java8Path;
	}
	
	public Path getJBSELibraryPath() {
		return this.jbsePath;
	}

	public void setJBSELibraryPath(Path jbsePath) {
		this.jbsePath = jbsePath;
	}
	
	public void setJBSELibraryPath(String jbsePath) {
		this.jbsePath = Paths.get(jbsePath);
	}
	
	public Path getJREPath() {
		return this.jrePath;
	}

	public void setJREPath(Path jrePath) {
		this.jrePath = jrePath;
	}
	
	public void setJREPath(String jrePath) {
		this.jrePath = Paths.get(jrePath);
	}
	
	public Path getEvosuitePath() {
		return this.evosuitePath;
	}
	
	public void setEvosuitePath(Path evosuitePath) {
		this.evosuitePath = evosuitePath;
	}
	
	public void setEvosuitePath(String evosuitePath) {
		this.evosuitePath = Paths.get(evosuitePath);
	}
	
	public Path getSushiLibPath() {
		return this.sushiPath;
	}
	
	public void setSushiLibPath(Path sushiPath) {
		this.sushiPath = sushiPath;
	}
	
	public void setSushiLibPath(String sushiPath) {
		this.sushiPath = Paths.get(sushiPath);
	}
	
	public int getEvosuiteTimeBudgetDuration() {
		return this.evosuiteTimeBudgetDuration;
	}
	
	public void setEvosuiteTimeBudgetDuration(int evosuiteTimeBudgetDuration) {
		this.evosuiteTimeBudgetDuration = evosuiteTimeBudgetDuration;
	}
	
	public TimeUnit getEvosuiteTimeBudgetUnit() {
		return this.evosuiteTimeBudgetUnit;
	}
	
	public void setEvosuiteTimeBudgetUnit(TimeUnit evosuiteTimeBudgetUnit) {
		this.evosuiteTimeBudgetUnit = evosuiteTimeBudgetUnit;
	}
	
	public long getGlobalTimeBudgetDuration() {
		return this.globalTimeBudgetDuration;
	}
	
	public void setGlobalTimeBudgetDuration(long globalTimeBudgetDuration) {
		this.globalTimeBudgetDuration = globalTimeBudgetDuration;
	}
	
	public TimeUnit getGlobalTimeBudgetUnit() {
		return this.globalTimeBudgetUnit;
	}
	
	public void setGlobalTimeBudgetUnit(TimeUnit globalTimeBudgetUnit) {
		this.globalTimeBudgetUnit = globalTimeBudgetUnit;
	}
	
	public long getTimeoutMOSATaskCreationDuration() {
		return this.timeoutMOSATaskCreationDuration;
	}
	
	public void setTimeoutMOSATaskCreationDuration(long timeoutMOSATaskCreationDuration) {
		this.timeoutMOSATaskCreationDuration = timeoutMOSATaskCreationDuration;
	}
	
	public TimeUnit getTimeoutMOSATaskCreationUnit() {
		return this.timeoutMOSATaskCreationUnit;
	}
	
	public void setTimeoutMOSATaskCreationUnit(TimeUnit timeoutMOSATaskCreationUnit) {
		this.timeoutMOSATaskCreationUnit = timeoutMOSATaskCreationUnit;
	}
	
	public int getNumMOSATargets() {
		return this.numMOSATargets;
	}
	
	public void setNumMOSATargets(int numMOSATargets) {
		this.numMOSATargets = numMOSATargets;
	}
	
	public boolean getUseMOSA() {
		return this.useMOSA;
	}
	
	public void setUseMOSA(boolean useMOSA) {
		this.useMOSA = useMOSA;
	}
	
	/* MAME */
	public Path getJBSEFilesPath() {
		return this.jbsefilesPath;
	}
	
	public void setJBSEFilesPath(Path jbsefilesPath) {
		this.jbsefilesPath = jbsefilesPath;
	}
	/* MAME */
	
	public void setHeapScope(String className, int scope) {
		if (className == null) {
			return;
		}
		if (this.heapScope == null) {
			this.heapScope = new HashMap<>();
		}
		this.heapScope.put(className, Integer.valueOf(scope));
	}
	
	/* MAME */
	public void setUninterpreted(Signature signature, String method) {
		if (signature == null) {
			return;
		}
		if (this.uninterpreted == null) {
			this.uninterpreted = new HashMap<Signature, String>();
		}
		this.uninterpreted.put(signature, method);
	}
	/* MAME */
	
	public void setHeapScopeUnlimited(String className) {
		if (this.heapScope == null) {
			return;
		}
		this.heapScope.remove(className);
	}
	
	public void setHeapScopeUnlimited() {
		this.heapScope = new HashMap<>();
	}
	
	public Map<String, Integer> getHeapScope() {
		return (this.heapScope == null ? null : Collections.unmodifiableMap(this.heapScope));
	}
	
	/* MAME */
	public Map<Signature, String> getUninterpreted() {
		return (this.uninterpreted == null ? null : Collections.unmodifiableMap(this.uninterpreted));
	}
	/* MAME */
	
	public void setCountScope(int countScope) {
		this.countScope = countScope;
	}
	
	public int getCountScope() {
		return this.countScope;
	}
	
	/* MAME */
	public int getDepthScope() {
		return this.depthScope;
	}
	
	public void setDepthScope(int depthScope) {
		this.depthScope = depthScope;
	}
	/* MAME */
	
	@Override
	public Options clone() {
		try {
			final Options theClone = (Options) super.clone();
			if (this.heapScope != null) {
				theClone.heapScope = new HashMap<>(this.heapScope);
			}
			return theClone;
		} catch (CloneNotSupportedException e) {
			//this should never happen
			throw new AssertionError("super.clone() raised CloneNotSupportedException");
		}
	}
}
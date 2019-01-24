package exec;

import static exec.Util.atJump;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;

import jbse.algo.exc.CannotManageStateException;
import jbse.apps.run.DecisionProcedureGuidance;
import jbse.apps.run.DecisionProcedureGuidanceJDI;
import jbse.apps.run.GuidanceException;
import jbse.apps.run.Run;
import jbse.apps.settings.ParseException;
import jbse.apps.settings.SettingsReader;
import jbse.bc.Signature;
import jbse.bc.exc.InvalidClassFileFactoryClassException;
import jbse.common.exc.ClasspathException;
import jbse.common.exc.UnexpectedInternalException;
import jbse.dec.DecisionProcedureAlgorithms;
import jbse.dec.DecisionProcedureAlwSat;
import jbse.dec.DecisionProcedureClassInit;
import jbse.dec.DecisionProcedureLICS;
import jbse.dec.DecisionProcedureSMTLIB2_AUFNIRA;
import jbse.dec.exc.DecisionException;
import jbse.jvm.Runner;
import jbse.jvm.RunnerBuilder;
import jbse.jvm.RunnerParameters;
import jbse.jvm.EngineParameters.BreadthMode;
import jbse.jvm.EngineParameters.StateIdentificationMode;
import jbse.jvm.Runner.Actions;
import jbse.jvm.exc.CannotBacktrackException;
import jbse.jvm.exc.CannotBuildEngineException;
import jbse.jvm.exc.EngineStuckException;
import jbse.jvm.exc.FailureException;
import jbse.jvm.exc.InitializationException;
import jbse.jvm.exc.NonexistingObservedVariablesException;
import jbse.mem.Clause;
import jbse.mem.State;
import jbse.mem.exc.ContradictionException;
import jbse.mem.exc.ThreadStackEmptyException;
import jbse.rewr.CalculatorRewriting;
import jbse.rewr.RewriterOperationOnSimplex;
import jbse.rules.ClassInitRulesRepo;
import jbse.rules.LICSRulesRepo;
import jbse.tree.StateTree.BranchPoint;

public class RunnerPath {
	private static final String COMMANDLINE_LAUNCH_Z3 = System.getProperty("os.name").toLowerCase().contains("windows") ? " /smt2 /in /t:10" : " -smt2 -in -t:10";
	
	private final String[] classpath;
	private final String z3Path;
	private final String outPath;
	private final String targetMethodName;
	private final TestCase testCase;
	private final RunnerParameters commonParamsGuided;
	private final RunnerParameters commonParamsGuiding;
	
	/* MAME */
	private String jbsefilesPath = "";
		
	public RunnerPath(Options o, EvosuiteResult item) {
		this.classpath = new String[o.getClassesPath().size() + 4];
		this.classpath[0] = o.getJREPath().toString();
		this.classpath[1] = o.getJBSELibraryPath().toString();
		this.classpath[2] = o.getEvosuitePath().toString();
		this.classpath[3] = o.getTmpBinTestsDirectoryPath().toString();
		for (int i = 4; i < this.classpath.length; ++i) {
			this.classpath[i] = o.getClassesPath().get(i - 4).toString();
		}
		this.z3Path = o.getZ3Path().toString();
		this.outPath = o.getOutDirectory().toString();
		this.targetMethodName = item.getTargetMethodName();
		this.testCase = item.getTestCase();
		//builds the template parameters object for the guided (symbolic) execution
		this.commonParamsGuided = new RunnerParameters();
		this.commonParamsGuided.setMethodSignature(item.getTargetClassName(), item.getTargetMethodDescriptor(), item.getTargetMethodName());
		this.commonParamsGuided.addClasspath(this.classpath);
		this.commonParamsGuided.setBreadthMode(BreadthMode.ALL_DECISIONS_NONTRIVIAL);
		
		/* MAME */
		if (o.getJBSEFilesPath() != null) {
			this.jbsefilesPath = o.getJBSEFilesPath().toString();
		}

		/* CODICE ORIGINALE */
		
		if (o.getHeapScope() != null) {
			for (Map.Entry<String, Integer> e : o.getHeapScope().entrySet()) {
				this.commonParamsGuided.setHeapScope(e.getKey(), e.getValue());
			}
		}
		if (o.getCountScope() > 0) {
			this.commonParamsGuided.setCountScope(o.getCountScope());
		}
		
		/* MAME */
		if (o.getDepthScope() > 0) {
			this.commonParamsGuided.setDepthScope(o.getDepthScope());
		}
		
		if(o.getUninterpreted() != null) {
			for (Map.Entry<Signature, String> e : o.getUninterpreted().entrySet()) {
				this.commonParamsGuided.addUninterpreted(e.getKey().getClassName(), e.getKey().getDescriptor(), e.getKey().getName(), e.getValue());
			}
		}
		/* MAME */
		
		
		//builds the template parameters object for the guiding (concrete) execution
		this.commonParamsGuiding = new RunnerParameters();
		this.commonParamsGuiding.addClasspath(this.classpath);
		this.commonParamsGuiding.setStateIdentificationMode(StateIdentificationMode.COMPACT);
		this.commonParamsGuiding.setBreadthMode(BreadthMode.ALL_DECISIONS);
		
	}

	private static class ActionsRunner extends Actions {
		private final int testDepth;
		private final DecisionProcedureGuidance guid;
		private int currentDepth = 0;
		private final ArrayList<State> stateList = new ArrayList<State>();
		private boolean savePreState = false;
		private State preState = null;
		private boolean atJump = false;
		private int jumpPC = 0;
		private final HashSet<String> coverage = new HashSet<>();
		private final Signature targetMethodSignature;
		private boolean atInitial = false;
		
		public ActionsRunner(int testDepth, DecisionProcedureGuidance guid, Signature targetMethodSignature) {
			this.testDepth = testDepth;
			this.guid = guid;
			this.targetMethodSignature = targetMethodSignature;
		}
		
		public ArrayList<State> getStateList() {
			return this.stateList;
		}
		
		public State getPreState() {
			return this.preState;
		}
		
		public boolean getAtJump() {
			return this.atJump;
		}
		
		public HashSet<String> getCoverage() {
			return this.coverage;
		}
		
		@Override
		public boolean atRoot() {
			if (this.testDepth == 0) {
				this.guid.endGuidance();
				this.savePreState = true;
			}
			return super.atRoot();
		}
		
		@Override
		public boolean atStepPre() {
			final State currentState = getEngine().getCurrentState();

			try {
				if (currentState.getCurrentMethodSignature().equals(targetMethodSignature)) {
					atInitial = true;
				}
			} catch (ThreadStackEmptyException e1) { }
			
			
			try {
				this.atJump = atJump(currentState.getInstruction());
				if (this.atJump) {
					this.jumpPC = currentState.getPC();
				}
			} catch (ThreadStackEmptyException e) {
				//this should never happen
				throw new RuntimeException(e); //TODO better exception!
			}
			if (this.savePreState) {
				this.preState = currentState.clone();
			}
			return super.atStepPre();
		}
		
		@Override
		public boolean atStepPost() {
            if (atInitial && this.guid != null) {  
            	try {
                      /* if (Run.this.engine.getCurrentState().getCurrentMethodSignature().equals( 
                                Run.this.guidance.getCurrentMethodSignature())) { 
                           Run.this.guidance.step();
                        }*/
            		this.guid.step();  
                    	
            	} catch (GuidanceException e) {
    				throw new RuntimeException(e); //TODO better exception!
    				//Run.this.err(ERROR_GUIDANCE_FAILED);
            		//Run.this.err(e);
            		//return true;
            	} catch (CannotManageStateException | ThreadStackEmptyException e) {
            		throw new UnexpectedInternalException(e);
            	}
            }

            if (this.atJump) {
				final State currentState = getEngine().getCurrentState();
				try {
				
					
					this.coverage.add(currentState.getCurrentMethodSignature().toString() + ":" + this.jumpPC + ":" + currentState.getPC());
				} catch (ThreadStackEmptyException e) {
					//this should never happen
					throw new RuntimeException(e); //TODO better exception!
				}
			}
			return super.atStepPost();
		}
		
		@Override
		public boolean atBranch(BranchPoint bp) {
			
			this.currentDepth++;				
			if (this.currentDepth == this.testDepth) {				
	
				this.guid.endGuidance();
				this.savePreState = true;
				
			} else if (this.currentDepth == this.testDepth + 1) {
				this.stateList.add(getEngine().getCurrentState().clone());
				getEngine().stopCurrentTrace();
				this.savePreState = false;
			}

			return super.atBranch(bp);
		}

		@Override
		public boolean atBacktrackPost(BranchPoint bp) {
			final State currentState = getEngine().getCurrentState();
			this.stateList.add(currentState.clone());
			getEngine().stopCurrentTrace();
			if (this.atJump) {
				try {
					this.coverage.add(currentState.getCurrentMethodSignature().toString() + ":" + this.jumpPC + ":" + currentState.getPC());
				} catch (ThreadStackEmptyException e) {
					//this should never happen
					throw new RuntimeException(e); //TODO better exception!
				}
			}

			return super.atBacktrackPost(bp);
		}

		@Override
		public void atEnd() {
			if (this.testDepth < 0) {
				final State finalState = this.getEngine().getCurrentState();
				this.stateList.add(finalState);
			}
			super.atEnd();
		}
	}

	//replicas of info stored in ActionsRunner
	
	private State initialState = null;
	private State preState = null;
	private boolean atJump = false;
	private HashSet<String> coverage = null;
	
	/**
	 * Performs symbolic execution of the target method guided by a test case,
	 * and returns the final state. Equivalent to {@link #runProgram(int) runProgram}{@code (-1).}
	 * {@link List#get(int) get}{@code (0)}.
	 * 
	 * @param testCase a {@link TestCase}, it will guide symbolic execution.
	 * @return the final {@link State}.
	 * @throws DecisionException
	 * @throws CannotBuildEngineException
	 * @throws InitializationException
	 * @throws InvalidClassFileFactoryClassException
	 * @throws NonexistingObservedVariablesException
	 * @throws ClasspathException
	 * @throws CannotBacktrackException
	 * @throws CannotManageStateException
	 * @throws ThreadStackEmptyException
	 * @throws ContradictionException
	 * @throws EngineStuckException
	 * @throws FailureException
	 * @throws ParseException 
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	public State runProgram()
			throws DecisionException, CannotBuildEngineException, InitializationException, 
			InvalidClassFileFactoryClassException, NonexistingObservedVariablesException, 
			ClasspathException, CannotBacktrackException, CannotManageStateException, 
			ThreadStackEmptyException, ContradictionException, EngineStuckException, 
			FailureException, FileNotFoundException, IOException, ParseException {
		return runProgram(-1).get(0); //depth -1 == never stop guidance
	}
	
	/**
	 * Performs symbolic execution of the target method guided by a test case 
	 * up to some depth, then peeks the states on the next branch.  
	 * 
	 * @param testCase a {@link TestCase}, it will guide symbolic execution.
	 * @param testDepth the maximum depth up to which {@code t} guides 
	 *        symbolic execution, or a negative value.
	 * @return a {@link List}{@code <}{@link State}{@code >} containing
	 *         all the states on branch at depth {@code stateDepth + 1}. 
	 *         In case {@code stateDepth < 0} executes the test up to the 
	 *         final state and returns a list containing only the final state.
	 * @throws DecisionException
	 * @throws CannotBuildEngineException
	 * @throws InitializationException
	 * @throws InvalidClassFileFactoryClassException
	 * @throws NonexistingObservedVariablesException
	 * @throws ClasspathException
	 * @throws CannotBacktrackException
	 * @throws CannotManageStateException
	 * @throws ThreadStackEmptyException
	 * @throws ContradictionException
	 * @throws EngineStuckException
	 * @throws FailureException
	 * @throws ParseException 
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	public List<State> runProgram(int testDepth)
			throws DecisionException, CannotBuildEngineException, InitializationException, 
			InvalidClassFileFactoryClassException, NonexistingObservedVariablesException, 
			ClasspathException, CannotBacktrackException, CannotManageStateException, 
			ThreadStackEmptyException, ContradictionException, EngineStuckException, 
			FailureException, FileNotFoundException, IOException, ParseException {

		//builds the parameters
		final RunnerParameters pGuided = this.commonParamsGuided.clone();
		final RunnerParameters pGuiding = this.commonParamsGuiding.clone();
		
		//sets the calculator
		final CalculatorRewriting calc = new CalculatorRewriting();
		calc.addRewriter(new RewriterOperationOnSimplex());
		pGuided.setCalculator(calc);
		pGuiding.setCalculator(calc);
		
		/* MAME */
		LICSRulesRepo licsRulesRepo = new LICSRulesRepo();
		ClassInitRulesRepo classInitRulesRepo = new ClassInitRulesRepo();
		
		
		if(jbsefilesPath != "") {
			System.out.println("invariante");
			SettingsReader settingsReader = new SettingsReader(jbsefilesPath);
			settingsReader.fillRulesLICS(licsRulesRepo);
			settingsReader.fillRulesClassInit(classInitRulesRepo);
		}
		
		pGuided.setDecisionProcedure(
				new DecisionProcedureAlgorithms(
						new DecisionProcedureClassInit( //useless?
								new DecisionProcedureLICS( //useless?
										new DecisionProcedureSMTLIB2_AUFNIRA(
												new DecisionProcedureAlwSat(), 
												calc, this.z3Path + COMMANDLINE_LAUNCH_Z3
											), 
												calc, licsRulesRepo), 
												calc, classInitRulesRepo), calc));
		pGuiding.setDecisionProcedure(
				new DecisionProcedureAlgorithms(
				new DecisionProcedureClassInit(
				new DecisionProcedureAlwSat(), 
				calc, classInitRulesRepo), 
				calc)); 
		
		/* CODICE ORIGINALE */
	
		//sets the guiding method (to be executed concretely)
		
		pGuiding.setMethodSignature(this.testCase.getClassName(), this.testCase.getMethodDescriptor(), this.testCase.getMethodName());
		
		//creates the guidance decision procedure and sets it
		final int numberOfHits = countNumberOfInvocations(this.testCase.getClassName(), this.targetMethodName);
	
		final DecisionProcedureGuidanceJDI guid = new DecisionProcedureGuidanceJDI(pGuided.getDecisionProcedure(),
				pGuided.getCalculator(), pGuiding, pGuided.getMethodSignature(), numberOfHits);
		pGuided.setDecisionProcedure(guid);
		
		//sets the actions
		final ActionsRunner actions = new ActionsRunner(testDepth, guid, pGuided.getMethodSignature());
		pGuided.setActions(actions);

		//builds the runner and runs it
		final RunnerBuilder rb = new RunnerBuilder();
		final Runner r = rb.build(pGuided);
		r.run();

		//outputs
		this.initialState = rb.getEngine().getInitialState();
		this.preState = actions.getPreState();
		this.atJump = actions.getAtJump();
		this.coverage = actions.getCoverage();
		
		//finalizes
		rb.getEngine().close();

		return actions.getStateList();
	}
	
	private static class CountVisitor extends VoidVisitorAdapter<Object> {
		final String methodName;
		int methodCallCounter = 0;

		public CountVisitor(String methodName) {
			this.methodName = methodName;
		}

		@Override
		public void visit(MethodCallExpr n, Object arg) {
			super.visit(n, arg);
			if (n.getNameAsString().equals(this.methodName)) {
				this.methodCallCounter++;
			}
		}
	}

	private int countNumberOfInvocations(String className, String methodName){
		//TODO use the whole signature of the target method to avoid ambiguities (that's quite hard)
		final CountVisitor v = new CountVisitor(methodName);
		try {
			final FileInputStream in = new FileInputStream(this.outPath + "/" + className + ".java");
			v.visit(JavaParser.parse(in), null);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		return v.methodCallCounter;
	}
	
	/**
	 * Must be invoked after an invocation of {@link #runProgram(TestCase, int) runProgram(tc, depth)}.
	 * Returns the initial state of symbolic execution.
	 * 
	 * @return a {@link State} or {@code null} if this method is invoked
	 *         before an invocation of {@link #runProgram(TestCase, int)}.
	 */
	public State getInitialState() {
		return this.initialState;
	}
	
	/**
	 * Must be invoked after an invocation of {@link #runProgram(TestCase, int) runProgram(tc, depth)}.
	 * Returns the state of symbolic execution at depth {@code depth}.
	 * 
	 * @return a {@link State} or {@code null} if this method is invoked
	 *         before an invocation of {@link #runProgram(TestCase, int)}.
	 */
	public State getPreState() {
		return this.preState;
	}
	
	/**
	 * Must be invoked after an invocation of {@link #runProgram(TestCase, int) runProgram(tc, depth)}.
	 * Returns whether the frontier is at a jump bytecode.
	 * 
	 * @return a {@code boolean}. If this method is invoked
	 *         before an invocation of {@link #runProgram(TestCase, int)}
	 *         always returns {@code false}.
	 */
	public boolean getAtJump() {
		return this.atJump;
	}
	
	/**
	 * Must be invoked after an invocation of {@link #runProgram(TestCase, int) runProgram(tc, depth)}.
	 * Returns the code branches covered by the execution.
	 * 
	 * @return a {@link HashSet}{@code <}{@link String}{@code >} where each {@link String} has the form
	 *         className:methodDescriptor:methodName:bytecodeFrom:bytecodeTo, or {@code null} if this method is invoked
	 *         before an invocation of {@link #runProgram(TestCase, int)}.
	 */
	public HashSet<String> getCoverage() {
		return this.coverage;
	}
}

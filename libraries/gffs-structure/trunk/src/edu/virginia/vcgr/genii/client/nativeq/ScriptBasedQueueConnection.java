package edu.virginia.vcgr.genii.client.nativeq;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.morgan.util.Pair;
import org.morgan.util.io.StreamUtils;

import edu.virginia.vcgr.genii.client.ContainerProperties;
import edu.virginia.vcgr.genii.client.bes.ResourceOverrides;
import edu.virginia.vcgr.genii.client.cmdLineManipulator.CmdLineManipulatorUtils;
import edu.virginia.vcgr.genii.client.pwrapper.ProcessWrapper;
import edu.virginia.vcgr.genii.client.pwrapper.ProcessWrapperException;
import edu.virginia.vcgr.genii.client.pwrapper.ProcessWrapperFactory;
import edu.virginia.vcgr.genii.client.security.PreferredIdentity;
import edu.virginia.vcgr.genii.cmdLineManipulator.CmdLineManipulatorException;
import edu.virginia.vcgr.genii.cmdLineManipulator.config.CmdLineManipulatorConfiguration;
import edu.virginia.vcgr.jsdl.OperatingSystemNames;

public abstract class ScriptBasedQueueConnection<ProviderConfigType extends ScriptBasedQueueConfiguration>
	extends AbstractNativeQueueConnection<ProviderConfigType>
{
	static private Log _logger = LogFactory.getLog(ScriptBasedQueueConnection.class);

	static public final String PATH_TO_DEV_NULL = "/dev/null";

	static public final String QUEUE_SCRIPT_RESULT_FILENAME = "queue.script.result";
	
	static public Map<String,String> Modules=new HashMap<String,String>();
	
	static boolean ModuleMapLoaded=false;

	protected ScriptBasedQueueConnection(File workingDirectory, ResourceOverrides resourceOverrides,
		CmdLineManipulatorConfiguration cmdLineManipulatorConf, NativeQueueConfiguration queueConfig, ProviderConfigType providerConfig)
		throws NativeQueueException
	{
		super(workingDirectory, resourceOverrides, cmdLineManipulatorConf, queueConfig, providerConfig);
	}

	@Override
	protected void initialize() throws NativeQueueException
	{
		super.initialize();

		String submitScriptName = providerConfiguration().submitScriptName();

		if (submitScriptName != null && submitScriptName.contains("/"))
			throw new IllegalArgumentException("SubmitScriptName must not contain any path characters (such as '/').");

		File bashBinary = providerConfiguration().bashBinary(new File("/bin/bash"));
		checkBinary(bashBinary);
	}

	final protected File getSubmitScript(File workingDirectory) throws IOException
	{
		String submitScriptName = providerConfiguration().submitScriptName();

		if (submitScriptName == null)
			return File.createTempFile("qsub", ".sh", workingDirectory);

		return new File(workingDirectory, submitScriptName);
	}

	final protected File getBashBinary()
	{
		return providerConfiguration().bashBinary(new File("/bin/bash"));
	}

	final protected Pair<File, List<String>> generateSubmitScript(File workingDirectory, ApplicationDescription application)
		throws NativeQueueException
	{
		PrintStream ps = null;
		List<String> finalCmdLine;
		File submitScript = null;

		try {
			submitScript = getSubmitScript(workingDirectory);
			ps = new PrintStream(submitScript);
			generateScriptHeader(ps, workingDirectory, application);
			generateQueueHeaders(ps, workingDirectory, application);
			generateQueueApplicationHeader(ps, workingDirectory, application);
			finalCmdLine = generateApplicationBody(ps, workingDirectory, application);
			generateQueueApplicationFooter(ps, workingDirectory, application);
			generateScriptFooter(ps, workingDirectory, application);

			submitScript.setExecutable(true, true);

			return new Pair<File, List<String>>(submitScript, finalCmdLine);
		} catch (IOException ioe) {
			throw new NativeQueueException("Unable to generate submit script.", ioe);
		} finally {
			StreamUtils.close(ps);

			// hmmm: abstract this as a function in gffs-basics for filesystem helper, but support passing a stream for the output to go to.
			if (_logger.isDebugEnabled()) {
				_logger.debug("full script about to be sent is:");
				int line = 0;
				BufferedReader br = null;
				try {
					FileInputStream fi = new FileInputStream(submitScript);
					br = new BufferedReader(new InputStreamReader(fi));
					String text;
					while ((text = br.readLine()) != null) {
						line++;
						_logger.debug(line + ": " + text);
					}
				} catch (FileNotFoundException e) {
					_logger.error("failed to show the contents of the submit script: " + submitScript, e);
				} catch (IOException e) {
					_logger.error("IOException while showing contents of the submit script: " + submitScript, e);
				} finally {
					StreamUtils.close(br);
				}
			}
		}
	}

	protected void generateScriptHeader(PrintStream script, File workingDirectory, ApplicationDescription application)
		throws NativeQueueException, IOException
	{
		script.format("#!%s\n\n", getBashBinary().getAbsolutePath());
	}

	protected void generateQueueHeaders(PrintStream script, File workingDirectory, ApplicationDescription application)
		throws NativeQueueException, IOException
	{
	}

	protected void generateQueueApplicationHeader(PrintStream script, File workingDirectory, ApplicationDescription application)
		throws NativeQueueException, IOException
	{
		Set<UnixSignals> signals = queueConfiguration().trapSignals();

		if (signals.size() > 0) {
			script.println("function signalTrap()");
			script.println("{");
			script.println("\techo \"Caught a signal -- killing process group.\" >&2");
			script.println("\tQUEUE_SCRIPT_RESULT=257");
			// we write the script result to a temp file then move it to ensure presence of result file is atomic.
			script.format("\techo $QUEUE_SCRIPT_RESULT > %s.tmp\n", QUEUE_SCRIPT_RESULT_FILENAME);
			script.format("\tmv %1$s.tmp %1$s\n", QUEUE_SCRIPT_RESULT_FILENAME);
			script.println("\tkill -9 0");
			script.println("}");
			script.println();
			for (UnixSignals signal : signals)
				script.format("trap signalTrap %s\n", signal);
		}
		script.format("export QUEUE_SCRIPT_RESULT=0\n");
	}

	protected void generateQueueApplicationFooter(PrintStream script, File workingDirectory, ApplicationDescription application)
		throws NativeQueueException, IOException
	{
		Set<UnixSignals> signals = queueConfiguration().trapSignals();

		if (signals.size() > 0) {
			script.println("QUEUE_JOB_ID=%%");
			script.println("wait %$QUEUE_JOB_ID");
			script.println("export QUEUE_SCRIPT_RESULT=$?");
		} else {
			script.format("\nexport QUEUE_SCRIPT_RESULT=$?\n");
		}
	}

	protected List<String> generateApplicationBody(PrintStream script, File workingDirectory, ApplicationDescription application)
		throws NativeQueueException, IOException
	{
		Set<UnixSignals> signals = queueConfiguration().trapSignals();
		List<String> newCmdLine = new Vector<String>();
		
		// 2020 May 27 CCH, add trap handling in qsub script
		// When scancel is called, slurm will send a SIGTERM (sent to this qsub script), then a SIGKILL if the job hasn't ended
		// Added here is a function that essentially passes on the SIGTERM to pwrapper, otherwise VMs will continue running
		script.println("\nterm_handler() { \n\tkill -TERM \"$pwrapperpid\" 2>/dev/null \necho \"Caught SIGTERM...\" >> ../Accounting/\"${PWD##*/}\"/vmwrapper_out.txt\n}");
		script.println("trap term_handler SIGTERM");
		
		script.format("cd \"%s\"\n", workingDirectory.getAbsolutePath());

		// CCH 2020 May 18
		// Make executable the appropriate wrapper if executable is an image
		// Prepend image path to arguments list if executable is an image
		String execName = application.getExecutableName();
		if (execName.endsWith(".simg") || execName.endsWith(".sif") || execName.endsWith(".qcow2")) {
			if (_logger.isDebugEnabled())
				_logger.debug("Handling image executable (.simg or .qcow2)...");
			String prefID = (PreferredIdentity.getCurrent() != null ? PreferredIdentity.getCurrent().getIdentityString() : null);
			String username = (prefID == null ? "Lancium" : prefID.split("CN=")[1].split(",")[0]);
			String[] execNameArray = execName.split("/");
			boolean usingLanciumImage = execNameArray[0].equals("Lancium");
			execName = execNameArray[execNameArray.length-1];
			String imagePath = usingLanciumImage ? "../Images/Lancium/" + execName : "../Images/" + username +  execName;
			// This should use getContainerProperty job BES directory
			if (imagePath.endsWith(".qcow2")) {
				execName = "../vmwrapper.sh";
			}
			else {
				execName = "../singularity-wrapper.sh";
			}
			if (_logger.isDebugEnabled())
				_logger.debug("Handling image executable: " + execName);
			if (Files.exists(Paths.get(imagePath))) {
				String MIME = Files.probeContentType(Paths.get(imagePath));
				if (_logger.isDebugEnabled())
					_logger.debug(imagePath + " exists with MIME type: " + MIME);
				if (MIME.contains("QEMU QCOW Image") || MIME.contains("run-singularity script executable")) {
					if (_logger.isDebugEnabled())
						_logger.debug(imagePath + " exists and has the correct MIME type.");
				}
				else {
					if (_logger.isDebugEnabled())
						_logger.debug(imagePath + " exists but has the wrong MIME type.");
					// 2020 May 27 CCH, not sure how to handle bad MIME type
					// Currently, set imagePath to something completely different so the original image does not execute
					imagePath = "../Images/Lancium/BAD_MIME";
				}
			}
			else {
				if (_logger.isDebugEnabled())
					_logger.debug(imagePath + " does not exist.");
			}
			Vector<String> arguments = new Vector<String>(application.getArguments());
			arguments.add(0, imagePath);
			application.setArguments(arguments);
		}
		else {
			if (!execName.contains("/")) {
				execName = String.format("./%s", execName);
			}
		}

		try {
			ResourceOverrides overrides = resourceOverrides();
			if (overrides == null)
				overrides = new ResourceOverrides();

			ProcessWrapper wrapper =
				ProcessWrapperFactory.createWrapper(getCommonDirectory(), overrides.operatingSystemName(), overrides.cpuArchitecture());

			// assemble job properties for cmdLineManipulators
			Map<String, Object> jobProperties = new HashMap<String, Object>();
			CmdLineManipulatorUtils.addBasicJobProperties(jobProperties, execName, application.getArguments());

			OperatingSystemNames desiredOperatingSystemType = overrides.operatingSystemName();
			OperatingSystemNames operatingSystemType =
				(desiredOperatingSystemType != null) ? desiredOperatingSystemType : OperatingSystemNames.mapFromCurrentOperatingSystem();

			File stdoutRedirect = application.getStdoutRedirect(workingDirectory);
			if (stdoutRedirect == null && operatingSystemType == OperatingSystemNames.LINUX) {
				stdoutRedirect = new File(PATH_TO_DEV_NULL);
			}
			File stderrRedirect = application.getStderrRedirect(workingDirectory);
			if (stderrRedirect == null && operatingSystemType == OperatingSystemNames.LINUX) {
				stderrRedirect = new File(PATH_TO_DEV_NULL);
			}

			URI variation = application.getSPMDVariation();
			if (variation != null) {
				stdoutRedirect = null;
				stderrRedirect = null;
			}

			CmdLineManipulatorUtils.addEnvProperties(jobProperties, application.getFuseMountPoint(), application.getEnvironment(),
				workingDirectory, application.getStdinRedirect(workingDirectory), stdoutRedirect, stderrRedirect,
				application.getResourceUsagePath(), wrapper.getPathToWrapper());
			CmdLineManipulatorUtils.addSPMDJobProperties(jobProperties, application.getSPMDVariation(), application.getNumProcesses(),
				application.getNumProcessesPerHost(), application.getThreadsPerProcess());

			/* ASG 10-20-2018. Adding code to take care of MODULES
			 	Get the environment strings; check for "MODULES_TO_LOAD".
			 	If found they will be of the form =moduleName;moduleName.
			 	Then look up each module name one at a time to get the translation to the local environment.
			 	Then, emit a "module load translatedName" to the script.
			*/ 
			boolean loaded=ModuleMapLoaded;
			synchronized (Modules) {
				if (loaded==false){
					String modulesSupported = ContainerProperties.getContainerProperties().getModuleList();
					if (modulesSupported!=null) {
						String []Supported=modulesSupported.split(";");
						for (int i=0;i<Supported.length;i++){
							String []mod=Supported[i].split(":"); // There had better be two strings
							if (mod.length==2) {
								Modules.put(mod[0],mod[1]);
							}								
						}
						ModuleMapLoaded=true;
						// The Modules map is now loaded.
					}
				}
			}

			if (application.getEnvironment() != null) {
				String modList=application.getEnvironment().get("MODULES_TO_LOAD");
				if (modList != null) {
					// We have a module list. Let's parse it.
					// Assuming the environment variable is the RHS of "MODULES_TO_LOAD=module1;module2" then split will do the job
					String []mods=modList.split(";");
					// Not sure why I had to introduce a local variable for this ... but it always behaved as if true.

					// Now for each of the mods requested by the user, see if it in Supported. 
					for (int i=0;i<mods.length;i++) {
						if (Modules.get(mods[i])!=null){
							// Found it
							script.println("module load " + Modules.get(mods[i]));
						}
						else {
							// Did not find it, need to throw a fault
							throw new NativeQueueException(String.format("Could not find binding for module: %s", mods[i]));
						}
					}
				}
			}
			
			// End of 10-20-2018 ASG module updates
			if (_logger.isDebugEnabled())
				_logger.debug("Trying to call cmdLine manipulators.");
			try {
				newCmdLine = CmdLineManipulatorUtils.callCmdLineManipulators(jobProperties, cmdLineManipulatorConf());
			} catch (CmdLineManipulatorException execption) {
				throw new NativeQueueException(String.format("CmdLine Manipulators failed: %s", execption.getMessage()));
			}

			// // for testing only - default cmdLine format to compare to transform
			// Vector<String> testCmdLine =
			// wrapper.formCommandLine(application.getFuseMountPoint(), application.getEnvironment(), workingDirectory,
			// application.getStdinRedirect(workingDirectory), stdoutRedirect, stderrRedirect, application.getResourceUsagePath(),
			// execName, application.getArguments().toArray(new String[application.getArguments().size()]));
			// if (_logger.isDebugEnabled())
			// _logger.debug(String.format("Previous cmdLine format with pwrapper only:\n %s", testCmdLine.toString()));

			boolean first = true;
			for (String element : newCmdLine) {
				if (!first)
					script.format(" ");
				first = false;
				script.format("\"%s\"", element);
			}
		} catch (ProcessWrapperException e) {
			throw new NativeQueueException("Unable to generate submission script.", e);
		}

		// 2020 May 27 CCH, part of signal handling in the qsub script
		// Commenting this out because we want to background the process so we can handle SLURM's SIGTERM
		// But it looks like part of what we're trying to do has already been done, so we may very well add this line back in
		//if (signals.size() > 0)
		script.print(" &");
		
		// 2020 May 27 CCH, part of signal handling in the qsub script
		script.println("\npwrapperpid=$!");
		script.println("wait \"$pwrapperpid\"");
		
		script.println();
		return newCmdLine;
	}

	protected void generateScriptFooter(PrintStream script, File workingDirectory, ApplicationDescription application)
		throws NativeQueueException, IOException
	{
		// we write the script result to a temp file then move it to ensure presence of result file is atomic.
		script.format("echo $QUEUE_SCRIPT_RESULT > %s.tmp\n", QUEUE_SCRIPT_RESULT_FILENAME);
		script.format("mv %1$s.tmp %1$s\n", QUEUE_SCRIPT_RESULT_FILENAME);
		script.println("exit $QUEUE_SCRIPT_RESULT");
	}

	protected String execute(ProcessBuilder builder) throws NativeQueueException
	{
		try {
			Collection<String> commandLine = builder.command();
			logProcess(commandLine);
			Process proc = builder.start();
			proc.getOutputStream().close();
			StreamCopier stdoutCopy = new StreamCopier(proc.getInputStream());
			StreamCopier stderrCopy = new StreamCopier(proc.getErrorStream());
			int result = proc.waitFor();
			// 2017-7-24 ASG. Fix to see if the command worked. If not, throw a fault.
			// That way we will not assume that the absence of information on a process means 
			// it has failed.
			// 2020-03-26 by ASG during the coronovirus lockdown.
			// mal-formed sbatch file can also cause  fault 
			logProcessResult(result, stdoutCopy, stderrCopy);
			if (result!=0) {
				throw new NativeQueueException("Unable to execute sbatch command: Error " + result + 
						", check permissions on path to working directory.\n Also check if the sbtch file is malformed or has no-longer-current #sbtch options.");
			}

			if (result == 0) {
				return stdoutCopy.getResult();
			}
			throw new ScriptExecutionException(commandLine, result, stderrCopy.getResult());
		} catch (InterruptedException ie) {
			throw new NativeQueueException("Unable to execute squeue command.", ie);
		} catch (IOException ioe) {
			throw new NativeQueueException("Unable to execute squeue command.", ioe);
		}
	}

	static private class StreamCopier implements Runnable
	{
		private Thread _myThread;
		private InputStream _source;
		private ByteArrayOutputStream _sink;
		private IOException _exception = null;

		public StreamCopier(InputStream source)
		{
			_source = source;
			_sink = new ByteArrayOutputStream();

			_myThread = new Thread(this);
			_myThread.setDaemon(true);
			_myThread.setName("Stream Copier");

			_myThread.start();
		}

		public void run()
		{
			try {
				StreamUtils.copyStream(_source, _sink);
			} catch (IOException ioe) {
				_exception = ioe;
			}
		}

		public String getResult() throws IOException
		{
			try {
				_myThread.join();
				if (_exception != null)
					throw _exception;

				return new String(_sink.toByteArray());
			} catch (InterruptedException ie) {
				return null;
			}
		}
	}

	protected void parseResult(String result, ScriptLineParser... parsers) throws NativeQueueException
	{
		BufferedReader reader = new BufferedReader(new StringReader(result));
		String line;

		try {
			while ((line = reader.readLine()) != null) {
				for (ScriptLineParser parser : parsers) {
					for (Pattern pattern : parser.getHandledPatterns()) {
						Matcher matcher = pattern.matcher(line);
						if (matcher.matches()) {
							parser.parseLine(matcher);
							break;
						}
					}
				}
			}
		} catch (IOException ioe) {
			_logger.error("caught exception in parseResult", ioe);
		}
	}

	/*
	 * we only log the results below if debugging level is enabled, since always enabling this gets way too noisy.
	 */

	static private void logProcess(Collection<String> commandLine)
	{
		if (_logger.isDebugEnabled())
			_logger.debug(new LazyEvaluatorList(commandLine));
	}

	static private void logProcessResult(int result, StreamCopier stdout, StreamCopier stderr)
	{
		if (_logger.isTraceEnabled())
			_logger.debug(new LazyEvaluatorResult(result, stdout, stderr));
	}

	static private class LazyEvaluatorResult
	{
		private int _result;
		private StreamCopier _stdout;
		private StreamCopier _stderr;

		public LazyEvaluatorResult(int result, StreamCopier stdout, StreamCopier stderr)
		{
			_result = result;
			_stdout = stdout;
			_stderr = stderr;
		}

		public String toString()
		{
			try {
				StringBuilder builder = new StringBuilder("Result:  " + _result + "\n");
				builder.append("Standard Out:\n");
				builder.append(_stdout.getResult());
				builder.append("\nStandard Err:\n");
				builder.append(_stderr.getResult());

				return builder.toString();
			} catch (IOException ioe) {
				return "Unable to get result from stream:  " + ioe.getLocalizedMessage();
			}
		}
	}

	static private class LazyEvaluatorList
	{
		private Collection<String> _list;

		public LazyEvaluatorList(Collection<String> list)
		{
			_list = list;
		}

		public String toString()
		{
			StringBuilder builder = new StringBuilder("Executing:  ");

			boolean first = true;
			for (String entry : _list) {
				if (!first)
					builder.append(" ");
				first = false;
				builder.append("\"" + entry + "\"");
			}

			return builder.toString();
		}
	}
}

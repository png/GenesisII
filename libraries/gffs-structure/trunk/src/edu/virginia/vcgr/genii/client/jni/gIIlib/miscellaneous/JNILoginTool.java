package edu.virginia.vcgr.genii.client.jni.gIIlib.miscellaneous;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import edu.virginia.vcgr.genii.client.cmd.CommandLineRunner;
import edu.virginia.vcgr.genii.client.context.ContextManager;
import edu.virginia.vcgr.genii.client.context.ICallingContext;
import edu.virginia.vcgr.genii.client.jni.gIIlib.JNILibraryBase;
import edu.virginia.vcgr.genii.security.TransientCredentials;

public class JNILoginTool extends JNILibraryBase
{
	static private Log _logger = LogFactory.getLog(JNILoginTool.class);

	public static Boolean login(String keystorePath, String password, String certPattern)
	{
		if (_logger.isTraceEnabled())
			_logger.trace(String.format("JNILoginTool::login(%s, %s, %s)", keystorePath, password, certPattern));

		tryToInitialize();

		CommandLineRunner runner = new CommandLineRunner();
		String[] args = { "login" };

		try {
			runner.runCommand(args, new OutputStreamWriter(System.out), new OutputStreamWriter(System.err),
				new BufferedReader(new InputStreamReader(System.in)));

			// Checks to make sure login worked
			ICallingContext callContext = ContextManager.getExistingContext();
			TransientCredentials transientCredentials = TransientCredentials.getTransientCredentials(callContext);

			if (transientCredentials != null && !transientCredentials.isEmpty())
				return true;
			else
				return false;
		} catch (Throwable e) {
			_logger.info("exception occurred in login", e);
			return false;
		}
	}
}

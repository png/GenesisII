package edu.virginia.vcgr.genii.client.cmd.tools.queue;

import edu.virginia.vcgr.genii.client.cmd.InvalidToolUsageException;
import edu.virginia.vcgr.genii.client.cmd.ToolException;
import edu.virginia.vcgr.genii.client.cmd.tools.BaseGridTool;
import edu.virginia.vcgr.genii.client.cmd.tools.ToolCategory;
import edu.virginia.vcgr.genii.client.io.LoadFileResource;
import edu.virginia.vcgr.genii.client.queue.QueueManipulator;

public class QRescheduleTool extends BaseGridTool
{

	static private final String _DESCRIPTION = "config/tooldocs/description/dqreschedule";
	static private final String _USAGE = "config/tooldocs/usage/uqreschedule";
	static final private String _MANPAGE = "config/tooldocs/man/qreschedule";

	public QRescheduleTool()
	{
		super(new LoadFileResource(_DESCRIPTION), new LoadFileResource(_USAGE), false, ToolCategory.EXECUTION);
		addManPage(new LoadFileResource(_MANPAGE));
	}

	@Override
	protected int runCommand() throws Throwable
	{
		QueueManipulator manipulator = new QueueManipulator(getArgument(0));
		String[] tickets = new String[numArguments() - 1];

		System.arraycopy(getArguments().toArray(), 1, tickets, 0, numArguments() - 1);

		/*
		 * Andrew actually doesn't want us to do this in the tool, he wants to do it outside the
		 * tool.
		 */
		// manipulator.configure(getArgument(1), 0);

		manipulator.rescheduleJobs(tickets);

		return 0;
	}

	@Override
	protected void verify() throws ToolException
	{
		if (numArguments() < 2)
			throw new InvalidToolUsageException("Must supply a queue path and at least 1 job ticket.");
	}
}
package edu.virginia.vcgr.genii.client.jsdl;

import java.io.File;

public class DirBasedGridFileSystem extends DirectoryBasedFileSystem
{
	static final long serialVersionUID = 0L;

	private String _sandbox;

	public DirBasedGridFileSystem(File directory, String sandbox)
	{
		super(directory);
		_sandbox = sandbox;
	}

	@Override
	protected boolean shouldDestroy()
	{
		return false;
	}

	public String getSandbox()
	{
		return _sandbox;
	}
}
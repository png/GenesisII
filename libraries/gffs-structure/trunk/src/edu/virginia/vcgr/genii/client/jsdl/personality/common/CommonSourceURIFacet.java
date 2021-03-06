package edu.virginia.vcgr.genii.client.jsdl.personality.common;

import java.net.URI;

import edu.virginia.vcgr.genii.client.jsdl.JSDLException;
import edu.virginia.vcgr.genii.client.jsdl.personality.def.DefaultSourceURIFacet;

public class CommonSourceURIFacet extends DefaultSourceURIFacet
{
	@Override
	public void consumeURI(Object currentUnderstanding, URI uri) throws JSDLException
	{
		((DataStagingUnderstanding) currentUnderstanding).setSourceURI(uri);
	}
}
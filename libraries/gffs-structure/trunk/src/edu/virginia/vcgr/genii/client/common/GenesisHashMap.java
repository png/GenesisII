package edu.virginia.vcgr.genii.client.common;

import java.util.HashMap;

import javax.xml.namespace.QName;

import org.apache.axis.message.MessageElement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class GenesisHashMap extends HashMap<QName, Object>
{
	/*
	 * this is intentionally incompatible with hashmap, since we don't want to directly serialize these objects as part of ensuring db
	 * compatibility.
	 */
	private static final long serialVersionUID = 23L;

	private static Log _logger = LogFactory.getLog(GenesisHashMap.class);

	public GenesisHashMap()
	{
		super();
	}

	public GenesisHashMap(int initialCapacity, float loadFactor)
	{
		super(initialCapacity, loadFactor);
	}

	public GenesisHashMap(int initialCapacity)
	{
		super(initialCapacity);
	}

	@Override
	public Object put(QName name, Object o)
	{
		if (_logger.isTraceEnabled())
			_logger.trace("put: " + name + " has type " + o.getClass().getCanonicalName());
		return super.put(name, o);
	}

	public MessageElement getMessageElement(QName which)
	{
		Object toReturn = get(which);
		if (toReturn == null)
			return null; // nothing there.
		if (toReturn instanceof MessageElement) {
			return (MessageElement) toReturn;
		} else {
			String msg = "type is not convertible to a message element: " + which;
			_logger.error(msg);
			throw new RuntimeException(msg);
		}
	}

	public String getString(QName which)
	{
		Object toReturn = get(which);
		if (toReturn == null)
			return null; // nothing there.
		if (_logger.isTraceEnabled())
			_logger.debug("get: " + which + " has type " + toReturn.getClass().getCanonicalName());
		if (toReturn instanceof String) {
			return (String) toReturn;
		} else {
			String msg = "type is not convertible to String: " + which;
			_logger.error(msg);
			throw new RuntimeException(msg);
		}
	}
}

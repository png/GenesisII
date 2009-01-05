package edu.virginia.vcgr.genii.client.resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;

import javax.xml.namespace.QName;

import org.apache.axis.message.MessageElement;
import org.morgan.util.io.StreamUtils;
import org.ws.addressing.ReferenceParametersType;

import edu.virginia.vcgr.genii.client.ser.Base64;

public class AddressingParameters
{
	static public final String GENII_REF_PARAMS_NS =
		"http://edu.virginia.vcgr.genii/ref-params";
	static public final String GENII_RESOURCE_KEY_REF_PARAM =
		"resource-key";
	static public final String GENII_RESOURCE_FORK_REF_PARAM =
		"resource-fork";
	static public final String GENII_ADDITIONAL_USER_INFO_REF_PARAM =
		"additiona-user-information";
	
	static public final QName GENII_RESOURCE_KEY_REF_PARAM_QNAME =
		new QName(GENII_REF_PARAMS_NS, GENII_RESOURCE_KEY_REF_PARAM);
	static public final QName GENII_RESOURCE_FORK_REF_PARAM_QNAME =
		new QName(GENII_REF_PARAMS_NS, GENII_RESOURCE_FORK_REF_PARAM);
	static public final QName GENII_ADDTIONAL_USER_INFO_REF_PARAM_QNAME =
		new QName(GENII_REF_PARAMS_NS, GENII_ADDITIONAL_USER_INFO_REF_PARAM);
	
	static private Serializable toObject(String encoded) 
		throws ResourceException
	{
		ObjectInputStream ois = null;
		
		try
		{
			ois = new ObjectInputStream(
				new ByteArrayInputStream(Base64.base64ToByteArray(encoded)));
			return (Serializable)ois.readObject();
		}
		catch (IOException ioe)
		{
			throw new ResourceException(
				"Unable to deserialize encoded reference parameter component.",
				ioe);
		}
		catch (ClassNotFoundException cnfe)
		{
			throw new ResourceException(
				"Unable to deserialize encoded reference parameter component.",
				cnfe);
		}
		finally
		{
			StreamUtils.close(ois);
		}
	}
	
	static private String toString(Object value) throws ResourceException
	{
		ByteArrayOutputStream baos = null;
		ObjectOutputStream oos = null;
		
		try
		{
			oos = new ObjectOutputStream( (baos = new ByteArrayOutputStream()) );
			oos.writeObject(value);
			oos.flush();
			return Base64.byteArrayToBase64(baos.toByteArray());
		}
		catch (IOException ioe)
		{
			throw new ResourceException(
				"Unable to encode reference parameter component.", ioe);
		}
		finally
		{
			StreamUtils.close(oos);
			StreamUtils.close(baos);
		}
	}
	
	private String _resourceKey;
	private Object _resourceForkInfo;
	private Serializable _additionalUserInfo;
	
	public AddressingParameters(ReferenceParametersType refParams) 
		throws ResourceException
	{
		this(null, null, null);
		
		if (refParams != null)
		{
			MessageElement []elements = refParams.get_any();
			if (elements != null)
			{
				for (MessageElement element : elements)
				{
					QName elementName = element.getQName();
					if (elementName.equals(GENII_RESOURCE_KEY_REF_PARAM_QNAME))
					{
						_resourceKey = element.getValue();
					} else if (elementName.equals(GENII_RESOURCE_FORK_REF_PARAM_QNAME))
					{
						String stringValue = element.getValue();
						if (stringValue != null)
						{
							_resourceForkInfo = toObject(stringValue);
						}
					} else if (elementName.equals(GENII_ADDTIONAL_USER_INFO_REF_PARAM_QNAME))
					{
						String stringValue = element.getValue();
						if (stringValue != null)
						{
							_additionalUserInfo = toObject(stringValue);
						}
					} else
					{
						throw new ResourceException(String.format(
							"Invalid reference parameter %s found.", elementName));
					}
				}
			}
		}
	}
	
	public AddressingParameters(String resourceKey,
		Object resourceForkInfo,
		Serializable additionalUserInfo)
	{
		_resourceKey = resourceKey;
		_resourceForkInfo = resourceForkInfo;
		_additionalUserInfo = additionalUserInfo;
	}
	
	public String getResourceKey()
	{
		return _resourceKey;
	}
	
	public void setResourceKey(String resourceKey)
	{
		
	}
	
	public Object getResourceForkInformation()
	{
		return _resourceForkInfo;
	}
	
	public void setResourceForkInformation(
		Object resourceForkInfo)
	{
		_resourceForkInfo = resourceForkInfo;
	}
	
	public Serializable getAdditionalUserInformation()
	{
		return _additionalUserInfo;
	}
	
	public void setAdditionalUserInformation(
		Serializable additionalUserInformation)
	{
		_additionalUserInfo = additionalUserInformation;
	}
	
	public ReferenceParametersType toReferenceParameters() 
		throws ResourceException
	{
		Collection<MessageElement> tmp = new ArrayList<MessageElement>(3);
		if (_resourceKey != null)
			tmp.add(new MessageElement(GENII_RESOURCE_KEY_REF_PARAM_QNAME, 
				_resourceKey));
		if (_resourceForkInfo != null)
			tmp.add(new MessageElement(GENII_RESOURCE_FORK_REF_PARAM_QNAME,
				toString(_resourceForkInfo)));
		if (_additionalUserInfo != null)
			tmp.add(new MessageElement(
				GENII_ADDTIONAL_USER_INFO_REF_PARAM_QNAME,
				toString(_additionalUserInfo)));
		
		if (tmp.isEmpty())
			return null;
		
		return new ReferenceParametersType(
			tmp.toArray(new MessageElement[tmp.size()]));
	}
}
package edu.virginia.vcgr.genii.client.jni.gIIlib.io;

import edu.virginia.vcgr.genii.client.jni.gIIlib.cache.CacheManager;
import edu.virginia.vcgr.genii.client.jni.gIIlib.io.handles.WindowsDirHandle;
import edu.virginia.vcgr.genii.client.jni.gIIlib.io.handles.WindowsFileHandle;
import edu.virginia.vcgr.genii.client.jni.gIIlib.io.handles.WindowsResourceHandle;

public class JNIRename {

	public static boolean rename(Integer fileHandle, String destination){					
		CacheManager manager = CacheManager.getInstance();		
		WindowsResourceHandle resourceHandle = manager.getHandle(fileHandle);			
		
		if(resourceHandle instanceof WindowsDirHandle){
			WindowsDirHandle dirHandle = (WindowsDirHandle)resourceHandle;
			return dirHandle.rename(destination);			
		}else{
			WindowsFileHandle winFileHandle = (WindowsFileHandle)resourceHandle;
			return winFileHandle.rename(destination);			
		}
	}
	
}

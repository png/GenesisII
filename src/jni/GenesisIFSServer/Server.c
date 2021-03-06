#include <tchar.h>
#include <stdlib.h>
#include <stdio.h>
#include <windows.h>
#include <signal.h>

#include <MakeGenesisIICalls.h>
#include "nulmrx.h"
#include <winioctl.h>
#include "server.h"

#define DEBUG						 0
#if DEBUG
#define GenesisPrint(Args) printf(Args)
#else
#define GenesisPrint(Args) NOP_FUNCTION
#endif

#define NUMBER_OF_THREADS			 3
#define THREAD_DELAY				100
                                                
//Global variables
HANDLE sem_handle;					//Semaphore to control access to device (necessary?)
HANDLE close_handle;				//Semaphore to control when to close
HANDLE GenesisControlHandle;		//Device handle	
BOOL isRunning = TRUE;				//Communication between threads (for closing)

//Defined in Test.c
void TestThread(GII_JNI_INFO rootInfo);

typedef struct _WORKER{
	HANDLE thread;
	struct _WORKER * nextWorker;
}*PWORKER, WORKER;

typedef struct _CommRequest {

    GENII_CONTROL_REQUEST    ControlRequest;
    char                     ControlRequestBuffer[USER_KERNEL_MAX_TRANSFER_SIZE + (3 * sizeof(long))];

} COMMREQUEST, *PCOMMREQUEST;

typedef struct _CommResponse {

    GENII_CONTROL_RESPONSE  ControlResponse;
	
	//Max 128 entries (65536 / 512) in QD
    char                    ControlResponseBuffer[USER_KERNEL_MAX_TRANSFER_SIZE + (3 * sizeof(long))];

} COMMRESPONSE, *PCOMMRESPONSE;

void printListing(char * listing, int size){
	int i;	
	ULONG length;
	ULONG fileid;

	if(size == JNI_ERR){
		GenesisPrint(("GenesisDrive: Error occurred while processing request\n"));
	}

	for(i =0; i< size; i++){		
		memcpy(&fileid, listing, sizeof(ULONG));		
		GenesisPrint(("GenesisDrive: File ID: %d ", fileid));
		listing += sizeof(ULONG);

		GenesisPrint(("GenesisDrive: File Type: %s ", listing));
		listing += strlen(listing) + 1;

		memcpy(&length, listing, sizeof(LONGLONG));
		GenesisPrint(("GenesisDrive: Size: %I64d ", length));
		listing += sizeof(LONGLONG);

		GenesisPrint(("GenesisDrive: Name: %s\n", listing));
		listing += strlen(listing) + 1;				
	}
}

//Copy listing and return total bytes saved
int copyListing(char * buffer, char ** listing, int size){

	char * pointer = buffer;
	
	//Max 64 bit length
	char lengthBuffer[9];
	LONGLONG length;

	int i;
	int bytesCopied = 0;

	__try{

		//{FileID}{D|F}{FileSize}{FileName}
		for(i =0; i < 4*size; i+=4){
			//Copy file id
			memcpy(lengthBuffer, listing[i], strlen(listing[i]));
			lengthBuffer[strlen(listing[i])] = '\0';
			length = atol(lengthBuffer);			
			memcpy(pointer, &length, sizeof(long));
			
			if(length != -1){
				//GenesisPrint(("GenesisDrive: FileID: %d ",length);
			}
			
			bytesCopied += sizeof(long);
			pointer += sizeof(long);

			//Copy type of File F | D
			memcpy(pointer, listing[i+1], strlen(listing[i+1]));
			pointer[strlen(listing[i+1])] = '\0';

			//GenesisPrint(("GenesisDrive: FileType: %s ", pointer));;

			bytesCopied += (int)strlen(listing[i+1]) + 1;
			pointer += strlen(listing[i+1]) + 1;		

			//Copy file length			
			length = _strtoi64(listing[i+2], NULL, 10);
			memcpy(pointer, &length, sizeof(LONGLONG));

			//GenesisPrint(("GenesisDrive: FileSize: %I64d ", length));; 

			bytesCopied += sizeof(LONGLONG);
			pointer += sizeof(LONGLONG);
			
			//Copy file name
			memcpy(pointer, listing[i+3], strlen(listing[i+3]));
			pointer[strlen(listing[i+3])] = '\0';

			//GenesisPrint(("GenesisDrive: FileName: %s\n", pointer));;
			
			bytesCopied += (int)strlen(listing[i+3]) + 1;
			pointer += strlen(listing[i+3]) + 1;
		}
	}
	__finally{
		if((bytesCopied == 0) && (size > 0)){
			printf("GenesisDrive: Some error occurred on copy (no bytes copied)\n");
		}
	}
	return bytesCopied;
}
BOOL GetRequest(PGENII_CONTROL_REQUEST PRequest,LPOVERLAPPED POverlapped)
{
    BOOL    status;
    DWORD   bytesReturned;    

	WaitForSingleObject(sem_handle, INFINITE);	
	status = DeviceIoControl(GenesisControlHandle,GENII_CONTROL_GET_REQUEST,
                             PRequest,sizeof(GENII_CONTROL_REQUEST),
                             PRequest,sizeof(GENII_CONTROL_REQUEST),
                             &bytesReturned,
                             POverlapped);
	ReleaseSemaphore(sem_handle, 1, NULL);

    if(!status) {
        DWORD error = GetLastError();
        if(error != ERROR_IO_PENDING) {
			printf("GenesisDrive: Something went wrong:  GetRequest - Status %x",GetLastError());
            return FALSE;
        }
    }    

    return TRUE;                             
}

BOOL SendResponse(PGENII_CONTROL_RESPONSE PResponse,LPOVERLAPPED POverlapped)
{
    BOOL    status;
    DWORD   bytesReturned;    


	WaitForSingleObject(sem_handle, INFINITE);	
	status = DeviceIoControl(GenesisControlHandle,GENII_CONTROL_SEND_RESPONSE,
                             PResponse,sizeof(GENII_CONTROL_RESPONSE),
                             NULL,0,
                             &bytesReturned,
                             POverlapped);
	ReleaseSemaphore(sem_handle, 1, NULL);

    if(!status) {
        DWORD error = GetLastError();
        if(error != ERROR_IO_PENDING) {
            printf("GenesisDrive: Something went wrong:  SendResponse - Status %x",GetLastError());
            return FALSE;
        }
    }        

    return TRUE;                             
}

BOOL SendUFSClose(LPOVERLAPPED POverlapped)
{
    BOOL    status;
    DWORD   bytesReturned;    

	WaitForSingleObject(sem_handle, INFINITE);	
	status = DeviceIoControl(GenesisControlHandle,GENII_CONTROL_UFS_STOP,
                             NULL,0,
                             NULL,0,
                             &bytesReturned,
                             POverlapped);
	ReleaseSemaphore(sem_handle, 1, NULL);

    if(!status) {
        DWORD error = GetLastError();
        if(error != ERROR_IO_PENDING) {
            printf("GenesisDrive: Something went wrong:  SendUFSClose - Status %x",GetLastError());
            return FALSE;
        }
    }        
    return TRUE;                             
}

void RepeatControlCSignalHandler(int signalNumber){
	signal(SIGINT, RepeatControlCSignalHandler);
	printf("GenesisDrive:  CTRL+C received, UFS is already shutting down\n");
}

void ControlCSignalHandler(int signalNumber){
	OVERLAPPED      OverlappedForCloser;
	BOOLEAN ret;

	//Setup other signal handler if many calls are made
	signal(SIGINT, RepeatControlCSignalHandler);

	printf("GenesisDrive: CTRL+C received, UFS is shutting down\n");

	//This will step all threads from waiting again
	isRunning = FALSE;
    
	//Acquire an event handler
	do{
		//Tell device you are ready to process 
	    memset(&OverlappedForCloser,0,sizeof(OverlappedForCloser));
		OverlappedForCloser.hEvent = CreateEvent(NULL,TRUE,FALSE,NULL);
	}while(OverlappedForCloser.hEvent == INVALID_HANDLE_VALUE);

    ret = SendUFSClose(&OverlappedForCloser);
    if(!ret) {
		printf("GenesisDrive: UFSClose not able to be sent!!!  Unable to abort, please restart\n");
        CloseHandle(OverlappedForCloser.hEvent);
        return;
    } 			

	//Wait to be alerted by the device that IO is waiting
    while(!HasOverlappedIoCompleted(&OverlappedForCloser)) {        
        Sleep(100);
    }

	GenesisPrint(("GenesisDrive: Signal handler has returned. Kernel is now consistent with a close\n"));

	//Signal main thread to finish clean up
	ReleaseSemaphore(close_handle, 1, NULL);

	CloseHandle(OverlappedForCloser.hEvent);
}

//Prepares a response 
void prepareResponse(PGII_JNI_INFO pMyInfo, PGENII_CONTROL_REQUEST request, 
		PGENII_CONTROL_RESPONSE response){		

	response->RequestID = request->RequestID;
    response->ResponseType = request->RequestType;	

	switch(request->RequestType){
		case GENII_QUERYDIRECTORY:
		{			
			//Status code in Query Directory = # of entries
			long directoryId;
			char *target;
			char *bufPtr = (char*) request->RequestBuffer;
			char ** directoryListing;

			//Get Directory id
			memcpy(&directoryId, bufPtr, sizeof(long));			
			bufPtr += sizeof(long);			
			
			if(request->RequestBufferLength > 0 && strlen(bufPtr) > 0){
				target = (char*) malloc(strlen(bufPtr) + 1);
				memcpy(target, bufPtr, strlen(bufPtr));
				target[strlen(bufPtr)] = '\0';
			}else{
				target = "";
			}

			//GenesisPrint(("GenesisDrive: Directory: %d Target: %s for Query Directory\n", directoryId, target));			
			response->StatusCode = genesisII_directory_listing(pMyInfo, &directoryListing, directoryId, target);

			//If an error, no copy is done
			response->ResponseBufferLength = response->StatusCode == -1 ? 0 : 
				copyListing(response->ResponseBuffer, directoryListing, response->StatusCode);
							
			break;
		}			
		case GENII_CREATE:{
			//Status code in Query Directory = # of entries
			char * path;			
			char *bufPtr = (char*) request->RequestBuffer;
			char ** listing;
			BOOLEAN isMalformed = TRUE;

			//Create parameters
			int requestedDeposition;
			int desiredAccess;
			int isDirectory;

			//Get path
			if(request->RequestBufferLength && strlen(bufPtr) > 0){
				path = (char*) malloc(strlen(bufPtr) + 1);
				memcpy(path, bufPtr, strlen(bufPtr));
				path[strlen(bufPtr)] = '\0';				
			}else{
				path = "";
			}						
			
			bufPtr += (request->RequestBufferLength > 0) ? strlen(bufPtr) + 1 : 0;

			__try{			
				memcpy(&requestedDeposition, bufPtr, sizeof(int));
				bufPtr += sizeof(int);
				memcpy(&desiredAccess, bufPtr, sizeof(int));
				bufPtr += sizeof(int);
				memcpy(&isDirectory, bufPtr, sizeof(int));
				bufPtr += sizeof(int);

				isMalformed = FALSE;
						
				//GenesisPrint(("GenesisDrive: Path: %s for Create, ", path);
				//GenesisPrint(("GenesisDrive: Options: %d, %d, %d\n", requestedDeposition, desiredAccess, isDirectory));

				response->StatusCode = genesisII_open(pMyInfo, path, requestedDeposition, desiredAccess, 
					isDirectory, &listing);				
				
				//If an error, no copy is done
				response->ResponseBufferLength = (response->StatusCode == -1) ? 0 : 
					copyListing(response->ResponseBuffer, listing, response->StatusCode);

			}__finally{
				if(isMalformed){
					response->StatusCode = -1;
				}
			}				
			break;
		}
		case GENII_CLOSE:{
			char *bufPtr = (char*) request->RequestBuffer;				
			long fileID;						
			int returnCode;
			BOOLEAN deleteOnCloseSpecified;

			//Get fileid
			memcpy(&fileID, bufPtr, sizeof(long));			
			bufPtr += sizeof(long);
		
			//Get Delete on Close
			memcpy(&deleteOnCloseSpecified, bufPtr, sizeof(char));
			response->ResponseBufferLength = sizeof(int);

			//GenesisPrint(("GenesisDrive: Closing file with fileID: %d. ", fileID));
			//GenesisPrint(("GenesisDrive: Delete Specified == %s\n", (deleteOnCloseSpecified ? "TRUE" : "FALSE")));
			returnCode = genesisII_close(pMyInfo, fileID, deleteOnCloseSpecified);

			memcpy(response->ResponseBuffer, &returnCode, sizeof(int));				

			break;
		}
		case GENII_RENAME:{
			char *bufPtr = (char*) request->RequestBuffer;
			long fileID;						
			char * target;

			int returnCode;			

			//Get fileid
			memcpy(&fileID, bufPtr, sizeof(long));			
			bufPtr += sizeof(long);

			//Get path
			if(strlen(bufPtr) > 0){
				target = (char*) malloc(strlen(bufPtr) + 1);
				memcpy(target, bufPtr, strlen(bufPtr));
				target[strlen(bufPtr)] = '\0';				
			}else{
				target = "";
			}						

			//GenesisPrint(("GenesisDrive: Renaming file with fileID: %d to %s. ", fileID, target));		
			returnCode = genesisII_rename(pMyInfo, fileID, target);

			memcpy(response->ResponseBuffer, &returnCode, sizeof(int));				
			break;
		}
		case GENII_READ:
		{			
			char *bufPtr = (char*) request->RequestBuffer;		
			long fileID, length;
			LONGLONG offset;

			//Get all three parameters
			memcpy(&fileID, bufPtr, sizeof(long));
			bufPtr += sizeof(int);
			memcpy(&offset, bufPtr, sizeof(LONGLONG));
			bufPtr += sizeof(LONGLONG);
			memcpy(&length, bufPtr, sizeof(long));

			//GenesisPrint(("GenesisDrive: Read started for file with fileID: %d, offset: %I64d, length %d\n", fileID, offset, length));
			response->ResponseBufferLength = genesisII_read(pMyInfo, fileID, response->ResponseBuffer, offset, length);			
			break;
		}
		case GENII_WRITE:
		{
			char *bufPtr = (char*) request->RequestBuffer;		
			long fileID, length;
			LONGLONG offset;

			response->ResponseBufferLength = 0;

			//Get all three parameters
			__try{
				memcpy(&fileID, bufPtr, sizeof(long));
				bufPtr += sizeof(long);
				memcpy(&offset, bufPtr, sizeof(LONGLONG));
				bufPtr += sizeof(LONGLONG);
				memcpy(&length, bufPtr, sizeof(long));
				bufPtr += sizeof(long);
				
				//GenesisPrint(("GenesisDrive: Write started for file with fileID: %d, offset: %I64d, length %d\n", fileID, offset, length);
				response->ResponseBufferLength = genesisII_write(pMyInfo, fileID, bufPtr, offset, length);				
			}			
			__finally{			
			}
			break;				
		}
		case GENII_TRUNCATEAPPEND:
		{
			char *bufPtr = (char*) request->RequestBuffer;		
			long fileID, length;
			LONGLONG offset;

			response->ResponseBufferLength = 0;

			//Get all three parameters
			__try{
				memcpy(&fileID, bufPtr, sizeof(long));
				bufPtr += sizeof(long);
				memcpy(&offset, bufPtr, sizeof(LONGLONG));
				bufPtr += sizeof(LONGLONG);
				memcpy(&length, bufPtr, sizeof(long));
				bufPtr += sizeof(long);
				
				//GenesisPrint(("GenesisDrive: TruncateAppend started for file with fileID: %d, offset: %I64d, length %d\n", fileID, offset, length);
				response->ResponseBufferLength = genesisII_truncate_append(pMyInfo, fileID, bufPtr, offset, length);				
			}			
			__finally{			
			}
			break;
		}
		default:
			//default action just copies request buff into response buff
            response->ResponseBufferLength = request->RequestBufferLength;

            memcpy(response->ResponseBuffer,
                   request->RequestBuffer,
                   request->RequestBufferLength);
		
			break;
	}
}

/*	
	Server thread with inverted calls
*/
DWORD WINAPI ServerThread(LPVOID parameter){	
    OVERLAPPED      GeniiRequestOverlapped;
    COMMREQUEST     GeniiRequest;
    COMMRESPONSE    GeniiResponse;
    BOOL            ret;	
	GII_JNI_INFO myInfo;	

	if(initializeJavaVMForThread(&myInfo) == -1){
		printf("GenesisDrive: Thread initialization failed\n");
		return -1;
	}
	
	//For this, we'll assume driver stays connected
	while(isRunning){		        
		DWORD bytesTransferred;

        //Tell device you are ready to process 
        memset(&GeniiRequestOverlapped,0,sizeof(GeniiRequestOverlapped));
        GeniiRequestOverlapped.hEvent = CreateEvent(NULL,TRUE,FALSE,L"ChangeMyName");
        
		if(GeniiRequestOverlapped.hEvent == INVALID_HANDLE_VALUE) {
			//try to acquire one next time around
            continue;
        }

        memset(&GeniiRequest,0,sizeof(GeniiRequest));

        GeniiRequest.ControlRequest.RequestBuffer = &(GeniiRequest.ControlRequestBuffer[0]);
        GeniiRequest.ControlRequest.RequestBufferLength = sizeof(GeniiRequest.ControlRequestBuffer);		

        ret = GetRequest(&(GeniiRequest.ControlRequest),&GeniiRequestOverlapped);
        if(!ret) {
            CloseHandle(GeniiRequestOverlapped.hEvent);
            continue;
        } 			

		//Wait to be alerted by the device that IO is waiting
        while(!HasOverlappedIoCompleted(&GeniiRequestOverlapped)) {            
            Sleep(THREAD_DELAY);
        }

		//Get the request				                                 
		ret = GetOverlappedResult(GenesisControlHandle,&GeniiRequestOverlapped,&bytesTransferred,FALSE);		        

		if(!isRunning){
			printf("GenesisDrive: Closing event signal after return from Kernel\n");
			CloseHandle(GeniiRequestOverlapped.hEvent);
			continue;
		}
		else{
			ResetEvent(GeniiRequestOverlapped.hEvent);
		}

		//This must be done before preparing the Response
		GeniiResponse.ControlResponse.ResponseBuffer = &(GeniiResponse.ControlResponseBuffer[0]);
		      
		//Call Genesis for appropriate response
		prepareResponse(&myInfo, &(GeniiRequest.ControlRequest), &(GeniiResponse.ControlResponse));		

		/*
		GenesisPrint(("GenesisDrive: Sending Response to Kernel Driver: Id= %d, Type = %x, Length %d\n\n", 
				 GeniiResponse.ControlResponse.RequestID,
				 GeniiResponse.ControlResponse.ResponseType,                 
				 GeniiResponse.ControlResponse.ResponseBufferLength));
		*/
		ret = SendResponse(&(GeniiResponse.ControlResponse),&GeniiRequestOverlapped);
		if(!ret) {             
			CloseHandle(GeniiRequestOverlapped.hEvent);
			continue;
		} 

		while(!HasOverlappedIoCompleted(&GeniiRequestOverlapped)) {		
			Sleep(THREAD_DELAY);
		}     		
        CloseHandle(GeniiRequestOverlapped.hEvent);
	}

	//Must detatch this thread from Genesis
	detatchThreadFromJVM();
	return 0;
}

int runMultiThreaded(PGII_JNI_INFO rootInfo){
	
	PWORKER head=NULL, last=NULL, current=NULL, next;
    DWORD dwThreadId;	
	int i;	

	sem_handle = CreateSemaphore(NULL, 1, 1, NULL);
	close_handle = CreateSemaphore(NULL, 0, 1, NULL);

	signal(SIGINT, ControlCSignalHandler);

	//Open device that deals with inverted calls
	GenesisControlHandle = CreateFile(DD_GENII_CONTROL_DEVICE_USER_NAME,
									GENERIC_READ|GENERIC_WRITE,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE,NULL,OPEN_EXISTING,
                                    FILE_ATTRIBUTE_NORMAL|FILE_FLAG_OVERLAPPED|FILE_FLAG_NO_BUFFERING,NULL);


	// We cannot continue if we cannot find the device
	if(GenesisControlHandle == INVALID_HANDLE_VALUE) {
		DWORD error = GetLastError();		
		printf("GenesisDrive:  Kernel Driver not opened correctly.  Most likely the system could not \
			find the driver.  Please reinstall and restart your system.  Error code %d\n", error);
		return 0; 
	}	

	for(i=0; i < NUMBER_OF_THREADS; i++){
		last = current;
		current = (PWORKER) malloc(sizeof(WORKER));			
		current->thread = CreateThread(NULL, 0, ServerThread, NULL, 0, &dwThreadId);
		current->nextWorker = NULL;
		if(last != NULL){
			last->nextWorker = current;
		}
		else{
			head = current;
		}	
	}

	printf("GenesisDrive: Now accepting requests\n");

	//Wait for close semaphore to be released
	WaitForSingleObject(close_handle, INFINITE);

	GenesisPrint(("GenesisDrive: Waiting for User Level Threads to Quit\n"));

	//Wait two seconds for other threads to catch up
	Sleep(2000);

	//Let's close these handles
	next = head;
	for(i=0; i < NUMBER_OF_THREADS; i++){
		__try {
			current = next;
			next = next->nextWorker;		
			CloseHandle(current->thread);
		}__except(EXCEPTION_EXECUTE_HANDLER){
			printf("GenesisDrive: Error on thread close\n");
		}
	}		

	genesisII_logout(rootInfo);	

	if(GenesisControlHandle != INVALID_HANDLE_VALUE) {				 
		CloseHandle(GenesisControlHandle);		
        GenesisControlHandle = INVALID_HANDLE_VALUE;
    }

	//Delete semaphore
	CloseHandle(sem_handle);
	CloseHandle(close_handle);
	return 0;
}

int main(int argc, char* argv[])
{
	int status = 0;
	int isSuccessful;

	GII_JNI_INFO rootInfo;

	printf("GenesisDrive: Initializing with %d threads with %d delay\n", NUMBER_OF_THREADS, THREAD_DELAY);

	//Initialize in root thread
	if(initializeJavaVM(NULL, &rootInfo) == -1){
		printf("GenesisDrive: Initialization Failed.  Shutting down\n");		
	} else {
		printf("GenesisDrive: Logging In to Genesis\n");
		isSuccessful = genesisII_login(&rootInfo, NULL, "keys", "sky");
		if(isSuccessful == JNI_ERR){
			printf("GenesisDrive: Login unsuccessful.  GenesisDrive requires login.  Shutting down\n");		
		} else {			
			status = runMultiThreaded(&rootInfo);
			//TestThread(rootInfo);		//For testing
		}	
	}
	printf("GenesisDrive: Shutdown Complete\n");		
	return status;
}
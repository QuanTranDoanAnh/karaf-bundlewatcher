package vn.quantda.example.osgi.bundlewatcher.remoteupgrader.exceptions;

public class InvalidArgumentException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = -2294766600820275662L;
	
	Exception innerException;
	
	public InvalidArgumentException(Exception e) {
		this.innerException = e;
	}

	public Exception getInnerException() {
		return innerException;
	}
	

}

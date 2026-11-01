package by.vs.dnaamrscreener.exception;

public class FileAlreadyProcessingException extends RuntimeException {

    public FileAlreadyProcessingException(String message) {
        super(message);
    }
}
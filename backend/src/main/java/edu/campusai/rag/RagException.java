package edu.campusai.rag;

public class RagException extends RuntimeException {
    private final int code;

    public RagException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() { return code; }
}

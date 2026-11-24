package ledger.query.search;

/** A search parameter the API rejects with 400 {@code INVALID_PARAMETER}. */
class InvalidParameterException extends RuntimeException {

    private final String parameter;

    InvalidParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    String parameter() {
        return parameter;
    }
}

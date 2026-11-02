package darpan.datasource

/**
 * DAR-BE-176. A call to a data-defined source failed. `code` is the system's own error code (read at the definition's
 * errorCodeAt), or HTTP_<status>, UNPARSEABLE, TRANSIENT. costRefused: the server refused the call's cost (the
 * definition's costRefusedCodes), so the caller may ask again for a smaller page.
 */
class DataSourceCallException extends RuntimeException {
    final String code
    final boolean costRefused

    DataSourceCallException(String code, String message, boolean costRefused = false) {
        super(message)
        this.code = code
        this.costRefused = costRefused
    }
}

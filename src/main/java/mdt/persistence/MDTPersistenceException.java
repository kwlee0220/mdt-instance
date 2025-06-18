package mdt.persistence;

import mdt.model.MDTException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTPersistenceException extends MDTException {
    private static final long serialVersionUID = 1L;

    public MDTPersistenceException(String msg) {
        super(msg);
    }

    public MDTPersistenceException(String msg, Throwable cause) {
        super(msg, cause);
    }
}

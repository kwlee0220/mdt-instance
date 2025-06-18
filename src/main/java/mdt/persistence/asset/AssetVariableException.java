package mdt.persistence.asset;

import mdt.model.MDTException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class AssetVariableException extends MDTException {
    private static final long serialVersionUID = 1L;

    public AssetVariableException(String msg) {
        super(msg);
    }

    public AssetVariableException(String msg, Throwable cause) {
        super(msg, cause);
    }
}

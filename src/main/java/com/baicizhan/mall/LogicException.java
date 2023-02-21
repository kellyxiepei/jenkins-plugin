package com.baicizhan.mall;

/**
 * @author Xie Wangyi(xiewangyi@baicizhan.com)
 */
public class LogicException extends Exception {
    public LogicException() {
        super();
    }

    public LogicException(String message) {
        super(message);
    }

    public LogicException(String message, Throwable cause) {
        super(message, cause);
    }

    public LogicException(Throwable cause) {
        super(cause);
    }

    protected LogicException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}

package com.capacitor.iab;

import com.getcapacitor.Logger;

public class InAppBrowser {

    public String echo(String value) {
        Logger.info("Echo", value);
        return value;
    }
}

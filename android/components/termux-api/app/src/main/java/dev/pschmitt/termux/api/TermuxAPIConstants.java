package dev.pschmitt.termux.api;

import dev.pschmitt.termux.shared.termux.TermuxConstants;
import static dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_API_PACKAGE_NAME;
import static dev.pschmitt.termux.shared.termux.TermuxConstants.TERMUX_PACKAGE_NAME;

public class TermuxAPIConstants {

    /**
     * Termux:API Receiver name.
     */
    public static final String TERMUX_API_RECEIVER_NAME = TERMUX_API_PACKAGE_NAME + ".TermuxApiReceiver"; // Default to "dev.pschmitt.termux.api.TermuxApiReceiver"

    /** The Uri authority for Termux:API app file shares */
    public static final String TERMUX_API_FILE_SHARE_URI_AUTHORITY = TERMUX_PACKAGE_NAME + ".api.sharedfiles"; // Default: "dev.pschmitt.termux.api.sharedfiles"

}

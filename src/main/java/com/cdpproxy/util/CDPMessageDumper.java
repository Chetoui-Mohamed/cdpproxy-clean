package com.cdpproxy.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.json.JSONObject;
import java.util.logging.Logger;

/**
 * Utility to dump CDP messages in a structured way for analysis
 */
public class CDPMessageDumper {
    private static final Logger logger = Logger.getLogger(CDPMessageDumper.class.getName());
    private static final String DUMP_FILE = "cdp-messages-dump.log";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    static {
        try {
            // Create or clear the dump file at startup
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(DUMP_FILE, false))) {
                writer.write("--- CDP Messages Dump Started: " + DATE_FORMAT.format(new Date()) + " ---\n\n");
                writer.flush();
            }
            logger.info("Created CDP messages dump file: " + new File(DUMP_FILE).getAbsolutePath());
        } catch (IOException e) {
            logger.severe("Failed to create CDP messages dump file: " + e.getMessage());
        }
    }

    /**
     * Dump a CDP message to the log file
     */
    public static void dumpMessage(String direction, String message) {
        try {
            String timestamp = DATE_FORMAT.format(new Date());

            // Parse the message as JSON for better formatting
            try {
                JSONObject json = new JSONObject(message);

                // Format the entry
                StringBuilder entry = new StringBuilder();
                entry.append("=== ").append(timestamp).append(" | ").append(direction).append(" ===\n");

                // Add message ID if present
                if (json.has("id")) {
                    entry.append("ID: ").append(json.get("id")).append("\n");
                }

                // Add method if present
                if (json.has("method")) {
                    entry.append("Method: ").append(json.getString("method")).append("\n");
                }

                // Add params if present
                if (json.has("params")) {
                    entry.append("Params: ").append(json.getJSONObject("params").toString(2)).append("\n");
                }

                // Add result if present
                if (json.has("result")) {
                    entry.append("Result: ").append(json.getJSONObject("result").toString(2)).append("\n");
                }

                // Add error if present
                if (json.has("error")) {
                    entry.append("Error: ").append(json.getJSONObject("error").toString(2)).append("\n");
                }

                // Add full message for reference
                entry.append("Full: ").append(json.toString(2)).append("\n");
                entry.append("\n"); // Add separator

                // Write to file
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(DUMP_FILE, true))) {
                    writer.write(entry.toString());
                    writer.flush();
                }
            } catch (Exception e) {
                // If not valid JSON, just dump the raw message
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(DUMP_FILE, true))) {
                    writer.write(timestamp + " | " + direction + " | " + message + "\n\n");
                    writer.flush();
                }
            }
        } catch (IOException e) {
            logger.severe("Failed to dump CDP message: " + e.getMessage());
        }
    }
}
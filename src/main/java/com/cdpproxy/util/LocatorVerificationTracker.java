package com.cdpproxy.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.json.JSONObject;
import org.json.JSONArray;

public class LocatorVerificationTracker {
    private static final Logger logger = Logger.getLogger(LocatorVerificationTracker.class.getName());

    // Log file configuration
    private static final String LOG_FILE = "broken-locators-detected.log";
    private static final String SHARED_JSON_FILE = "broken-locators-for-healing.json";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // Maps to track locator verification status
    private static final Map<String, Map<String, LocatorStatus>> sessionLocators = new ConcurrentHashMap<>();

    // Time to wait before confirming a locator is broken
    private static final long CONFIRMATION_DELAY_MS = 5000; // 5 seconds

    // Static initializer for log file
    static {
        try {
            File logFile = new File(LOG_FILE);
            if (!logFile.exists()) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile))) {
                    writer.write("=== BROKEN LOCATOR DETECTION LOG - STARTED " + DATE_FORMAT.format(new Date()) + " ===\n\n");
                    writer.write("TIMESTAMP | SESSION | TYPE | SELECTOR | ATTEMPTS | DURATION_MS | REASON\n");
                    writer.write("----------------------------------------------------------------------------\n");
                    writer.flush();
                }
                logger.info("Created broken locator detection log file: " + logFile.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.severe("Failed to initialize log file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Class to track the status of a locator
     */
    private static class LocatorStatus {
        final String selector;
        final String type;
        final long firstSeenTime;
        long lastVerificationAttempt;
        boolean verified;
        boolean reported;
        int attempts;

        public LocatorStatus(String selector, String type) {
            this.selector = selector;
            this.type = type;
            this.firstSeenTime = System.currentTimeMillis();
            this.lastVerificationAttempt = this.firstSeenTime;
            this.verified = false;
            this.reported = false;
            this.attempts = 1;
        }

        public void recordAttempt() {
            this.attempts++;
            this.lastVerificationAttempt = System.currentTimeMillis();
        }
    }

    /**
     * Register a new locator or update an existing one
     */
    public static void trackLocator(String sessionId, String selector, String type) {
        if (selector == null || selector.isEmpty()) {
            return;
        }

        // Get or create session map
        Map<String, LocatorStatus> sessionMap = sessionLocators.computeIfAbsent(
                sessionId, id -> new ConcurrentHashMap<>());

        // Get or create status for this selector
        LocatorStatus status = sessionMap.get(selector);

        if (status == null) {
            // First time seeing this locator
            status = new LocatorStatus(selector, type);
            sessionMap.put(selector, status);
        } else {
            // We've seen this locator before, record the attempt
            status.recordAttempt();
        }
    }

    /**
     * Mark a locator as verified (working)
     */
    public static void verifyLocator(String sessionId, String selector) {
        if (selector == null || selector.isEmpty()) {
            return;
        }

        Map<String, LocatorStatus> sessionMap = sessionLocators.get(sessionId);
        if (sessionMap == null) {
            return;
        }

        LocatorStatus status = sessionMap.get(selector);
        if (status != null && !status.verified) {
            status.verified = true;
        }
    }

    /**
     * Check for locators that should be reported as broken
     * This should be called periodically
     */
    public static void checkForBrokenLocators() {
        long now = System.currentTimeMillis();

        // Check each session
        for (Map.Entry<String, Map<String, LocatorStatus>> sessionEntry : sessionLocators.entrySet()) {
            String sessionId = sessionEntry.getKey();
            Map<String, LocatorStatus> locators = sessionEntry.getValue();

            // Find locators that are unverified and have been so for a while
            for (LocatorStatus status : locators.values()) {
                if (!status.verified && !status.reported) {
                    // Calculate time since first seen
                    long duration = status.lastVerificationAttempt - status.firstSeenTime;

                    // Skip common selectors that might be legitimate but don't always
                    // trigger verification due to how they're used
                    if (status.selector.equals(":scope > LEGEND") ||
                            status.selector.equals("body") ||
                            status.selector.equals("html")) {
                        status.verified = true; // Mark as verified to avoid reporting
                        continue;
                    }

                    // For quick attempts with minimal duration, only report if we've seen multiple attempts
                    if (duration < 100 && status.attempts < 2) {
                        continue; // Skip reporting elements that were seen only briefly
                    }

                    // Require more evidence for potentially broken locators
                    boolean likelyBroken =
                            // Has been attempted multiple times
                            (status.attempts >= 3 ||
                                    // OR took significant time trying to resolve
                                    duration >= 1000) &&
                                    // AND enough time has passed since the last attempt
                                    (now - status.lastVerificationAttempt) > CONFIRMATION_DELAY_MS;

                    if (likelyBroken) {
                        // This locator is likely broken - report it
                        logBrokenLocator(sessionId, status);
                        status.reported = true;
                    }
                }
            }

            // Clean up old entries
            locators.entrySet().removeIf(entry -> {
                LocatorStatus status = entry.getValue();
                // Remove verified locators after a while (to prevent memory leaks)
                return (status.verified && (now - status.lastVerificationAttempt > 60000)) ||
                        // Remove reported broken locators after a while
                        (status.reported && (now - status.lastVerificationAttempt > 60000));
            });
        }

        // Remove empty session maps
        sessionLocators.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * Log a broken locator with details
     */
    private static void logBrokenLocator(String sessionId, LocatorStatus status) {
        try {
            // Skip ":scope > LEGEND" selectors
            if (status.selector.equals(":scope > LEGEND")) {
                return;
            }

            String timestamp = DATE_FORMAT.format(new Date());
            long duration = status.lastVerificationAttempt - status.firstSeenTime;

            // Create reason - ONLY for unverified locators
            String reason = "UNVERIFIED_LOCATOR after " + status.attempts +
                    " attempts over " + duration + "ms";

            // Format the log message with type added
            String logEntry = String.format("%s | %s | %s | %s | %d | %d | %s",
                    timestamp,
                    sessionId,
                    status.type,      // Added locator type
                    status.selector,
                    status.attempts,
                    duration,
                    reason
            );

            // Write to log file
            synchronized (LocatorVerificationTracker.class) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
                    writer.write(logEntry + "\n");
                    writer.flush();
                }
            }

            // Write to shared JSON file for integration with robotframework-heal
            writeToSharedJsonFile(sessionId, status, reason, duration);

        } catch (IOException e) {
            logger.severe("Failed to log broken locator: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Write broken locator info to a shared JSON file for integration with robotframework-heal
     */
    private static void writeToSharedJsonFile(String sessionId, LocatorStatus status, String reason, long duration) {
        try {
            // Create a JSON object with all necessary information
            JSONObject locatorInfo = new JSONObject();
            locatorInfo.put("sessionId", sessionId);
            locatorInfo.put("selector", status.selector);
            locatorInfo.put("type", status.type);
            locatorInfo.put("timestamp", System.currentTimeMillis());
            locatorInfo.put("reason", reason);
            locatorInfo.put("attempts", status.attempts);
            locatorInfo.put("duration", duration);

            // Read existing file if it exists
            JSONArray existingData = new JSONArray();
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(SHARED_JSON_FILE);
                if (java.nio.file.Files.exists(path)) {
                    String content = new String(java.nio.file.Files.readAllBytes(path));
                    if (!content.trim().isEmpty()) {
                        existingData = new JSONArray(content);
                    }
                }
            } catch (Exception e) {
                logger.warning("Could not read existing shared JSON file: " + e.getMessage());
                // Continue with empty array if file doesn't exist or is invalid
            }

            // Add new locator info
            existingData.put(locatorInfo);

            // Write back to file
            synchronized (LocatorVerificationTracker.class) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(SHARED_JSON_FILE))) {
                    writer.write(existingData.toString(2));
                    writer.flush();
                }
            }

            logger.info("Added broken locator to shared JSON file for healing: " + status.selector);

        } catch (Exception e) {
            logger.severe("Failed to write to shared JSON file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Get the current verification status of a locator
     */
    public static boolean isLocatorVerified(String sessionId, String selector) {
        Map<String, LocatorStatus> sessionMap = sessionLocators.get(sessionId);
        if (sessionMap == null) {
            return false;
        }

        LocatorStatus status = sessionMap.get(selector);
        return status != null && status.verified;
    }

    /**
     * Initialize and start the periodic checker
     */
    public static void initialize() {
        // Create empty shared JSON file if it doesn't exist
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(SHARED_JSON_FILE);
            if (!java.nio.file.Files.exists(path)) {
                java.nio.file.Files.createFile(path);
                java.nio.file.Files.write(path, "[]".getBytes());
                logger.info("Created shared JSON file for integration with robotframework-heal: " + path.toAbsolutePath());
            }
        } catch (Exception e) {
            logger.warning("Could not create shared JSON file: " + e.getMessage());
        }

        // Start a thread to periodically check for broken locators
        Thread checkerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    checkForBrokenLocators();
                    Thread.sleep(2000); // Check every 2 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.severe("Error in locator verification checker: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });

        checkerThread.setDaemon(true);
        checkerThread.setName("LocatorVerificationChecker");
        checkerThread.start();

        logger.info("Locator verification tracker initialized");
    }
}
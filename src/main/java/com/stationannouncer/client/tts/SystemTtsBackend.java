package com.stationannouncer.client.tts;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.MathHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fallback backend that shells out to the operating system's voice:
 * {@code say} on macOS, PowerShell's System.Speech on Windows, {@code espeak}
 * on Linux. Commands are built as argument arrays (no shell interpolation) and
 * the text is stripped of control characters first.
 */
@Environment(EnvType.CLIENT)
class SystemTtsBackend {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "StationAnnouncer-TTS");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Process current;
    /** Bumped by stop(); a speak task started before the bump kills its own process. */
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
    private boolean warned;

    boolean say(String text, float volume) {
        String sanitized = sanitize(text);
        if (sanitized.isBlank()) {
            return false;
        }
        float clamped = MathHelper.clamp(volume, 0.0f, 1.0f);
        List<String> command = buildCommand(sanitized, clamped, ClientConfig.get().voice);
        if (command == null) {
            return false;
        }
        int startedGeneration = generation.get();
        executor.submit(() -> {
            try {
                stopCurrent();
                if (startedGeneration != generation.get()) {
                    return; // stop() arrived before we could start speaking
                }
                Process process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                current = process;
                if (startedGeneration != generation.get()) {
                    stopCurrent(); // stop() raced our launch: kill the fresh process too
                    return;
                }
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (!warned) {
                    warned = true;
                    StationAnnouncer.LOGGER.warn("System TTS command failed: {}", command.get(0), e);
                }
            }
        });
        return true;
    }

    void stop() {
        generation.incrementAndGet();
        stopCurrent();
    }

    private void stopCurrent() {
        Process process = current;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
        current = null;
    }

    private static List<String> buildCommand(String text, float volume, String voice) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String voiceName = voice == null ? "" : voice.replaceAll("\\p{Cntrl}", "").trim();
        List<String> command = new ArrayList<>();
        if (os.contains("mac")) {
            command.add("say");
            if (!voiceName.isEmpty()) {
                command.add("-v");
                command.add(voiceName);
            }
            // "say" reads embedded [[volm x]] commands (0..1) for volume control.
            command.add(String.format(Locale.ROOT, "[[volm %.2f]] %s", volume, text));
        } else if (os.contains("win")) {
            int percent = Math.round(volume * 100);
            String escaped = text.replace("'", "''");
            String selectVoice = voiceName.isEmpty()
                    ? ""
                    : "try { $s.SelectVoice('" + voiceName.replace("'", "''") + "'); } catch {} ";
            command.add("powershell");
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-Command");
            command.add("Add-Type -AssemblyName System.Speech; "
                    + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                    + selectVoice
                    + "$s.Volume = " + percent + "; "
                    + "$s.Speak('" + escaped + "');");
        } else if (os.contains("linux") || os.contains("nix")) {
            command.add("espeak");
            if (!voiceName.isEmpty()) {
                command.add("-v");
                command.add(voiceName);
            }
            command.add("-a");
            command.add(String.valueOf(Math.round(volume * 200))); // espeak amplitude 0..200
            command.add(text);
        } else {
            return null;
        }
        return command;
    }

    private static String sanitize(String text) {
        return text.replaceAll("\\p{Cntrl}", " ").trim();
    }
}

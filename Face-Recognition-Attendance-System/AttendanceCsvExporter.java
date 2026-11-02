import java.awt.Component;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Exports today's six-column attendance records without modifying the database. */
public final class AttendanceCsvExporter {
    private AttendanceCsvExporter() { }

    public static void exportTodayAttendance(Component parent, AttendanceDAO attendanceDAO) {
        Object[][] rows = attendanceDAO.getTodayAttendanceRecords();
        String[] headers = {"Roll No", "Student Name", "Date", "Check-in Time", "Check-out Time", "Status"};

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Today's Attendance");
        chooser.setSelectedFile(new java.io.File("attendance_" + LocalDate.now() + ".csv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        Path output = chooser.getSelectedFile().toPath();
        String fileName = output.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            output = output.resolveSibling(fileName + ".csv");
        }
        if (Files.exists(output)) {
            int overwrite = JOptionPane.showConfirmDialog(parent,
                    "This file already exists. Replace it?", "Confirm Overwrite",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF'); // Excel-friendly UTF-8 BOM
            writeCsvRow(writer, headers);
            for (Object[] row : rows) {
                String[] values = new String[headers.length];
                for (int i = 0; i < headers.length; i++) {
                    values[i] = row != null && i < row.length && row[i] != null
                            ? String.valueOf(row[i]) : "";
                }
                writeCsvRow(writer, values);
            }
            JOptionPane.showMessageDialog(parent,
                    "Attendance exported successfully!\nFile: " + output.toAbsolutePath()
                            + "\nRecords: " + rows.length,
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(parent,
                    "Could not export CSV:\n" + ex.getMessage(),
                    "Export Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void writeCsvRow(BufferedWriter writer, String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            String value = values[i] == null ? "" : values[i];
            writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.newLine();
    }
}

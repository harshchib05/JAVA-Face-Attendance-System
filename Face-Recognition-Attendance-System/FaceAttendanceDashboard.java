
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.*;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.opencv.opencv_face.LBPHFaceRecognizer;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;
import static org.bytedeco.opencv.global.opencv_core.CV_32SC1;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;

public class FaceAttendanceDashboard extends JFrame {

    private final Color bg = new Color(16, 24, 32);
    private final Color panel = new Color(27, 43, 54);
    private final Color green = new Color(60, 200, 145);
    private final Color white = new Color(240, 245, 248);
    private final Color muted = new Color(165, 180, 192);

    private JPanel content;
    private JLabel totalValue, presentValue, absentValue, rateValue;
    private JLabel statusLabel;
    private DefaultTableModel tableModel;

    private final StudentDAO studentDAO = new StudentDAO();
    private final AttendanceDAO attendanceDAO = new AttendanceDAO();

    // Camera & Recognition state
    private OpenCVFrameGrabber grabber;
    private CascadeClassifier faceDetector;
    private final AtomicBoolean isCameraRunning = new AtomicBoolean(false);
    private JLabel cameraViewLabel;
    private Thread cameraThread;
    // Latest detected face crop used only by the face-registration screen.
    private volatile Mat latestDetectedFace;
    private volatile String lastRecognizedRoll = "";
    private volatile long lastAttendanceMillis = 0L;

    public FaceAttendanceDashboard() {
        setTitle("FaceAttend AI - Attendance Management System");
        setSize(1180, 750);
        setMinimumSize(new Dimension(950, 650));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initHaarCascade();

        getContentPane().setBackground(bg);
        setLayout(new BorderLayout());

        add(createSidebar(), BorderLayout.WEST);

        content = new JPanel(new BorderLayout(15, 15));
        content.setBackground(bg);
        content.setBorder(BorderFactory.createEmptyBorder(25, 25, 25, 25));
        add(content, BorderLayout.CENTER);

        showDashboard();
        setVisible(true);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopCamera();
            }
        });
    }

    private void initHaarCascade() {
        try {
            File cascadeFile = new File("haarcascade_frontalface_default.xml");
            if (cascadeFile.exists()) {
                faceDetector = new CascadeClassifier(cascadeFile.getAbsolutePath());
                System.out.println("[+] Haar Cascade loaded successfully from: " + cascadeFile.getAbsolutePath());
            } else {
                System.out.println("[-] Haar Cascade XML not found locally.");
            }
        } catch (Exception e) {
            System.err.println("[-] Error loading Haar Cascade: " + e.getMessage());
        }
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setPreferredSize(new Dimension(230, 0));
        sidebar.setBackground(new Color(23, 35, 46));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createEmptyBorder(25, 15, 20, 15));

        JLabel logo = new JLabel("  FaceAttend AI");
        logo.setFont(new Font("SansSerif", Font.BOLD, 22));
        logo.setForeground(green);
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(logo);

        JLabel subtitle = new JLabel("  Smart Attendance System");
        subtitle.setForeground(muted);
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(subtitle);
        sidebar.add(Box.createVerticalStrut(35));

        addNavButton(sidebar, "Dashboard", () -> { stopCamera(); showDashboard(); });
        addNavButton(sidebar, "Students", () -> { stopCamera(); showStudents(); });
        addNavButton(sidebar, "Face Recognition", this::showRecognition);
        addNavButton(sidebar, "Attendance Records", () -> { stopCamera(); showAttendance(); });
        addNavButton(sidebar, "About Project", () -> { stopCamera(); showAbout(); });

        sidebar.add(Box.createVerticalGlue());

        JLabel statusBox = new JLabel(
            "<html><div style='width:165px; color:#3cc891;'><b>System Active</b><br>" +
            "<span style='color:#a5b4c0; font-size:11px;'>MySQL Connected<br>Database: face_attendance</span></div></html>"
        );
        statusBox.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));
        sidebar.add(statusBox);

        return sidebar;
    }

    private void addNavButton(JPanel sidebar, String text, Runnable action) {
        JButton button = new JButton(text);
        button.setMaximumSize(new Dimension(200, 43));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFont(new Font("SansSerif", Font.PLAIN, 14));
        button.setForeground(Color.WHITE);
        button.setBackground(new Color(23, 35, 46));
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> action.run());

        sidebar.add(button);
        sidebar.add(Box.createVerticalStrut(5));
    }

    private void preparePage(String title, String description) {
        content.removeAll();

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(bg);

        JLabel heading = new JLabel(title);
        heading.setForeground(white);
        heading.setFont(new Font("SansSerif", Font.BOLD, 26));

        JLabel desc = new JLabel(description);
        desc.setForeground(muted);
        desc.setFont(new Font("SansSerif", Font.PLAIN, 13));

        header.add(heading);
        header.add(Box.createVerticalStrut(6));
        header.add(desc);

        content.add(header, BorderLayout.NORTH);
    }

    private JPanel createCard(String title, JLabel value, Color accent) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(panel);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(49, 68, 81)),
            BorderFactory.createEmptyBorder(18, 18, 18, 18)
        ));

        JLabel label = new JLabel(title);
        label.setForeground(muted);
        label.setFont(new Font("SansSerif", Font.PLAIN, 14));

        value.setForeground(accent);
        value.setFont(new Font("SansSerif", Font.BOLD, 28));

        card.add(label);
        card.add(Box.createVerticalStrut(14));
        card.add(value);

        return card;
    }

    private void updateStats() {
        int total = studentDAO.getTotalStudents();
        int present = attendanceDAO.getPresentTodayCount();
        int absent = Math.max(0, total - present);
        double rate = total == 0 ? 0.0 : (present * 100.0 / total);

        if (totalValue != null) totalValue.setText(String.valueOf(total));
        if (presentValue != null) presentValue.setText(String.valueOf(present));
        if (absentValue != null) absentValue.setText(String.valueOf(absent));
        if (rateValue != null) rateValue.setText(String.format("%.0f%%", rate));
    }

    private void showDashboard() {
        preparePage(
            "Attendance Dashboard",
            "Welcome to FaceAttend AI | Today: " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy"))
        );

        JPanel body = new JPanel(new BorderLayout(15, 20));
        body.setBackground(bg);

        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 12));
        cards.setBackground(bg);

        totalValue = new JLabel("0");
        presentValue = new JLabel("0");
        absentValue = new JLabel("0");
        rateValue = new JLabel("0%");

        cards.add(createCard("Total Students", totalValue, white));
        cards.add(createCard("Present Today", presentValue, green));
        cards.add(createCard("Absent Today", absentValue, new Color(255, 170, 95)));
        cards.add(createCard("Attendance Rate", rateValue, new Color(130, 175, 255)));

        updateStats();

        JPanel lower = new JPanel(new GridLayout(1, 2, 15, 15));
        lower.setBackground(bg);

        JPanel actions = createSection("Quick Actions");
        addAction(actions, "Register Face", this::showFaceRegistration);
        addAction(actions, "Open Face Recognition", this::showRecognition);
        addAction(actions, "View Attendance Records", this::showAttendance);
        addAction(actions, "Manage Students", this::showStudents);

        JPanel info = createSection("Live System Status");
        statusLabel = new JLabel(
            "<html><div style='width:280px; line-height:1.6; color:#a5b4c0;'>" +
            "<b>Database:</b> <span style='color:#3cc891;'>Connected</span> (MySQL face_attendance)<br>" +
            "<b>Camera Support:</b> OpenCV 4.9.0 / JavaCV<br>" +
            "<b>Face Detector:</b> Haar Cascade Frontal Face<br>" +
            "<b>Status:</b> Ready for real-time recognition</div></html>"
        );
        info.add(statusLabel);

        lower.add(actions);
        lower.add(info);

        body.add(cards, BorderLayout.NORTH);
        body.add(lower, BorderLayout.CENTER);

        content.add(body, BorderLayout.CENTER);
        refresh();
    }

    private JPanel createSection(String title) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(panel);
        section.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(49, 68, 81)),
            BorderFactory.createEmptyBorder(18, 18, 18, 18)
        ));

        JLabel heading = new JLabel(title);
        heading.setForeground(green);
        heading.setFont(new Font("SansSerif", Font.BOLD, 17));

        section.add(heading);
        section.add(Box.createVerticalStrut(16));
        return section;
    }

    private void addAction(JPanel panel, String text, Runnable action) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(320, 42));
        button.setBackground(Color.WHITE);
        button.setForeground(Color.BLACK);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        button.addActionListener(e -> action.run());

        panel.add(button);
        panel.add(Box.createVerticalStrut(10));
    }

    private void showStudents() {
        preparePage("Student Management", "Registered students stored in MySQL database");

        JPanel body = new JPanel(new BorderLayout(10, 10));
        body.setBackground(bg);

        String[] columns = {"ID", "Roll No", "Student Name", "Department"};
        Object[][] data = studentDAO.getAllStudents();
        JTable table = createTable(columns, data);

        // Keep the student actions in a dedicated, visible bar ABOVE the table.
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        toolbar.setBackground(bg);
        toolbar.setPreferredSize(new Dimension(0, 54));

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setPreferredSize(new Dimension(110, 34));
        refreshBtn.setBackground(panel);
        refreshBtn.setForeground(white);
        refreshBtn.setOpaque(true);
        refreshBtn.setContentAreaFilled(true);
        refreshBtn.setFocusPainted(false);
        refreshBtn.addActionListener(e -> showStudents());

        JButton editBtn = new JButton("Edit Selected Student");
        editBtn.setPreferredSize(new Dimension(190, 34));
        editBtn.setBackground(new Color(255, 193, 7));
        editBtn.setForeground(Color.BLACK);
        editBtn.setOpaque(true);
        editBtn.setContentAreaFilled(true);
        editBtn.setFocusPainted(false);
        editBtn.setBorder(BorderFactory.createLineBorder(new Color(220, 160, 0)));
        editBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        editBtn.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();

            if (selectedRow == -1) {
                JOptionPane.showMessageDialog(
                        this,
                        "Please select a student first!",
                        "No Student Selected",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            int modelRow = table.convertRowIndexToModel(selectedRow);
            int studentId = Integer.parseInt(
                    table.getModel().getValueAt(modelRow, 0).toString());

            String oldRoll = table.getModel().getValueAt(modelRow, 1).toString();
            String oldName = table.getModel().getValueAt(modelRow, 2).toString();
            String oldDept = table.getModel().getValueAt(modelRow, 3).toString();

            JTextField rollField = new JTextField(oldRoll);
            JTextField nameField = new JTextField(oldName);
            JTextField deptField = new JTextField(oldDept);

            JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
            form.add(new JLabel("Roll Number:"));
            form.add(rollField);
            form.add(new JLabel("Student Name:"));
            form.add(nameField);
            form.add(new JLabel("Department:"));
            form.add(deptField);

            int result = JOptionPane.showConfirmDialog(
                    this,
                    form,
                    "Edit Student",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);

            if (result == JOptionPane.OK_OPTION) {
                String roll = rollField.getText().trim();
                String name = nameField.getText().trim();
                String dept = deptField.getText().trim();

                if (roll.isEmpty() || name.isEmpty() || dept.isEmpty()) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Please fill in all fields!",
                            "Validation Error",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }

                if (studentDAO.updateStudent(studentId, roll, name, dept)) {
                    JOptionPane.showMessageDialog(this, "Student updated successfully!");
                    showStudents();
                    updateStats();
                } else {
                    JOptionPane.showMessageDialog(
                            this,
                            "Update failed. Check the roll number or database.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton addBtn = new JButton("+ Add New Student");
        addBtn.setPreferredSize(new Dimension(175, 34));
        addBtn.setBackground(green);
        addBtn.setForeground(new Color(16, 24, 32));
        addBtn.setOpaque(true);
        addBtn.setContentAreaFilled(true);
        addBtn.setFocusPainted(false);
        addBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        addBtn.addActionListener(e -> promptAddStudent());

        toolbar.add(refreshBtn);
        toolbar.add(editBtn);
        toolbar.add(addBtn);

        body.add(toolbar, BorderLayout.NORTH);
        body.add(new JScrollPane(table), BorderLayout.CENTER);

        content.add(body, BorderLayout.CENTER);
        refresh();
    }

    private void promptAddStudent() {
        JTextField rollField = new JTextField();
        JTextField nameField = new JTextField();
        JTextField deptField = new JTextField();

        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Roll Number:"));
        form.add(rollField);
        form.add(new JLabel("Student Name:"));
        form.add(nameField);
        form.add(new JLabel("Department:"));
        form.add(deptField);

        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                "Add New Student",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String roll = rollField.getText().trim();
            String name = nameField.getText().trim();
            String dept = deptField.getText().trim();

            if (roll.isEmpty() || name.isEmpty() || dept.isEmpty()) {
                JOptionPane.showMessageDialog(
                        this,
                        "Please fill in all fields!",
                        "Validation Error",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (studentDAO.addStudent(roll, name, dept)) {
                JOptionPane.showMessageDialog(
                        this,
                        "Student added successfully to MySQL!",
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
                showStudents();
                updateStats();
            } else {
                JOptionPane.showMessageDialog(
                        this,
                        "Failed to add student. Roll number might already exist.",
                        "Database Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void showAttendance() {
        preparePage("Attendance Records", "Today's live attendance records from database");

        JPanel body = new JPanel(new BorderLayout(10, 10));
        body.setBackground(bg);

        String[] columns = {"Roll No", "Student Name", "Date", "Check-in Time", "Check-out Time", "Status"};
        Object[][] data = attendanceDAO.getTodayAttendanceRecords();

        tableModel = new DefaultTableModel(data, columns);
        JTable table = new JTable(tableModel);
        styleTable(table);

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomBar.setBackground(bg);

        JButton markManualBtn = new JButton("Mark Manual Attendance");
        markManualBtn.setBackground(new Color(60, 150, 220));
        markManualBtn.setForeground(Color.WHITE);
        markManualBtn.setOpaque(true);
        markManualBtn.setContentAreaFilled(true);
        markManualBtn.setFocusPainted(false);
        markManualBtn.setBorderPainted(false);
        markManualBtn.addActionListener(e -> promptManualAttendance());

        JButton exportBtn = new JButton("Export CSV");
        exportBtn.setBackground(green);
        exportBtn.setForeground(bg);
        exportBtn.setOpaque(true);
        exportBtn.setContentAreaFilled(true);
        exportBtn.setFocusPainted(false);
        exportBtn.setBorderPainted(false);
        exportBtn.addActionListener(e ->
            AttendanceCsvExporter.exportTodayAttendance(this, attendanceDAO)
        );

        JButton refreshBtn = new JButton("Refresh Records");
        refreshBtn.setBackground(panel);
        refreshBtn.setForeground(Color.WHITE);
        refreshBtn.setOpaque(true);
        refreshBtn.setContentAreaFilled(true);
        refreshBtn.setFocusPainted(false);
        refreshBtn.setBorderPainted(false);
        refreshBtn.addActionListener(e -> showAttendance());

        bottomBar.add(refreshBtn);
        bottomBar.add(exportBtn);
        bottomBar.add(markManualBtn);

        body.add(new JScrollPane(table), BorderLayout.CENTER);
        body.add(bottomBar, BorderLayout.SOUTH);

        content.add(body, BorderLayout.CENTER);
        refresh();
    }

    private void promptManualAttendance() {
        Object[][] students = studentDAO.getAllStudents();
        if (students.length == 0) {
            JOptionPane.showMessageDialog(this, "No students registered.");
            return;
        }

        String[] rollNos = new String[students.length];
        for (int i = 0; i < students.length; i++) {
            rollNos[i] = students[i][1] + " - " + students[i][2];
        }

        String selected = (String) JOptionPane.showInputDialog(
            this, "Select Student to mark attendance:", "Manual Attendance",
            JOptionPane.QUESTION_MESSAGE, null, rollNos, rollNos[0]
        );

        if (selected != null) {
            String roll = selected.split(" - ")[0];
            String[] statuses = {"Absent"};
            String status = (String) JOptionPane.showInputDialog(
                this, "Manual attendance (Absent only; Present requires face checkout):", "Attendance Status",
                JOptionPane.QUESTION_MESSAGE, null, statuses, statuses[0]
            );

            if (status != null) {
                if (attendanceDAO.markAttendanceByRollNo(roll, status)) {
                    JOptionPane.showMessageDialog(this, "Attendance marked for " + roll + " (" + status + ")!");
                    showAttendance();
                    updateStats();
                } else {
                    JOptionPane.showMessageDialog(this, "Error updating attendance in database.");
                }
            }
        }
    }

    private void promptAddStudentForFace() {
        JTextField rollField = new JTextField();
        JTextField nameField = new JTextField();
        JTextField deptField = new JTextField();

        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Roll Number:"));
        form.add(rollField);
        form.add(new JLabel("Student Name:"));
        form.add(nameField);
        form.add(new JLabel("Department:"));
        form.add(deptField);

        int result = JOptionPane.showConfirmDialog(
                this, form, "Add New Student for Face Registration",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        String roll = rollField.getText().trim();
        String name = nameField.getText().trim();
        String dept = deptField.getText().trim();

        if (roll.isEmpty() || name.isEmpty() || dept.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please fill in Roll Number, Name, and Department.",
                    "Missing Details", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (studentDAO.addStudent(roll, name, dept)) {
            JOptionPane.showMessageDialog(this,
                    "Student added to database. Now select " + roll + " - " + name
                            + " and capture their face.",
                    "Student Added", JOptionPane.INFORMATION_MESSAGE);
            showFaceRegistration();
        } else {
            JOptionPane.showMessageDialog(this,
                    "Student add nahi hua. Roll Number duplicate ho sakta hai ya database error hai.",
                    "Add Student Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showFaceRegistration() {
        stopCamera();
        preparePage("Register Student Face", "Capture a face image and save it against an existing student");

        Object[][] students = studentDAO.getAllStudents();
        if (students == null || students.length == 0) {
            JPanel emptyPanel = new JPanel();
            emptyPanel.setBackground(bg);
            emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
            JLabel empty = new JLabel("No students found. Add a new student to register their face.");
            empty.setForeground(white);
            empty.setAlignmentX(Component.CENTER_ALIGNMENT);
            JButton addFirstStudent = new JButton("+ Add New Student");
            addFirstStudent.setAlignmentX(Component.CENTER_ALIGNMENT);
            addFirstStudent.addActionListener(e -> promptAddStudentForFace());
            emptyPanel.add(Box.createVerticalGlue());
            emptyPanel.add(empty);
            emptyPanel.add(Box.createVerticalStrut(14));
            emptyPanel.add(addFirstStudent);
            emptyPanel.add(Box.createVerticalGlue());
            content.add(emptyPanel, BorderLayout.CENTER);
            refresh();
            return;
        }

        String[] choices = new String[students.length];
        for (int i = 0; i < students.length; i++) {
            choices[i] = students[i][1] + " - " + students[i][2];
        }

        JPanel main = new JPanel(new BorderLayout(15, 15));
        main.setBackground(bg);
        cameraViewLabel = new JLabel("Select a student, then start the camera.", SwingConstants.CENTER);
        cameraViewLabel.setPreferredSize(new Dimension(640, 480));
        cameraViewLabel.setOpaque(true);
        cameraViewLabel.setBackground(new Color(10, 15, 20));
        cameraViewLabel.setForeground(muted);
        cameraViewLabel.setBorder(BorderFactory.createLineBorder(new Color(49, 68, 81), 2));
        main.add(cameraViewLabel, BorderLayout.CENTER);

        JPanel controls = createSection("Face Registration");
        JComboBox<String> studentChoice = new JComboBox<>(choices);
        studentChoice.setMaximumSize(new Dimension(280, 34));
        controls.add(new JLabel("Choose student (Roll No - Name):"));
        controls.add(Box.createVerticalStrut(6));
        controls.add(studentChoice);
        controls.add(Box.createVerticalStrut(8));

        JButton addStudent = new JButton("+ Add New Student");
        addStudent.setMaximumSize(new Dimension(280, 34));
        addStudent.addActionListener(e -> promptAddStudentForFace());
        controls.add(addStudent);
        controls.add(Box.createVerticalStrut(16));

        JButton start = new JButton("Start Camera");
        start.addActionListener(e -> startCamera());
        JButton stop = new JButton("Stop Camera");
        stop.addActionListener(e -> stopCamera());
        JButton capture = new JButton("Capture & Save Face");
        capture.setBackground(green);
        capture.setForeground(bg);
        capture.setOpaque(true);
        capture.setContentAreaFilled(true);
        capture.addActionListener(e -> {
            if (!isCameraRunning.get()) {
                JOptionPane.showMessageDialog(this, "Pehle Start Camera dabao.", "Camera Not Started",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            Mat face = latestDetectedFace;
            if (face == null || face.empty()) {
                JOptionPane.showMessageDialog(this,
                        "Abhi face detect nahi hua. Camera ke saamne seedha dekho aur dobara try karo.",
                        "Face Not Detected", JOptionPane.WARNING_MESSAGE);
                return;
            }

            Mat faceCopy = face.clone();
            String selected = (String) studentChoice.getSelectedItem();
            if (selected == null) {
                JOptionPane.showMessageDialog(this, "Pehle student select karo.");
                return;
            }
            String roll = selected.split(" - ", 2)[0].trim();
            String safeRoll = roll.replaceAll("[^a-zA-Z0-9._-]", "_");
            try {
                Path directory = Paths.get("face_data", safeRoll);
                Files.createDirectories(directory);
                Path imagePath = directory.resolve("face_" + System.currentTimeMillis() + ".png");
                boolean saved = imwrite(imagePath.toAbsolutePath().toString(), faceCopy);
                if (saved) {
                    JOptionPane.showMessageDialog(this,
                            "Face image save ho gayi for Roll No: " + roll + "\\nFile: " + imagePath
                                    + "\\nNote: image capture saved hai; identity matching abhi alag se implement/test karni hai.",
                            "Face Captured", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this, "Face image save nahi ho saki. OpenCV image writer check karo.",
                            "Save Failed", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Face save error: " + ex.getMessage(),
                        "Save Failed", JOptionPane.ERROR_MESSAGE);
            } finally {
                faceCopy.close();
            }
        });

        controls.add(start);
        controls.add(Box.createVerticalStrut(8));
        controls.add(stop);
        controls.add(Box.createVerticalStrut(8));
        controls.add(capture);
        controls.add(Box.createVerticalStrut(14));
        JLabel note = new JLabel("<html><div style='width:245px;color:#a5b4c0;'>"
                + "Face detect hone par green box dikhega. Capture button detected face crop ko "
                + "face_data/RollNumber/ mein save karega. Ye abhi registration image capture hai, "
                + "automatic identity recognition nahi.</div></html>");
        controls.add(note);

        main.add(controls, BorderLayout.EAST);
        content.add(main, BorderLayout.CENTER);
        refresh();
    }

    private void showRecognition() {
        preparePage("Face Recognition Console", "Live camera stream and real-time face detection");

        JPanel mainBox = new JPanel(new BorderLayout(15, 15));
        mainBox.setBackground(bg);

        cameraViewLabel = new JLabel("Click 'Start Camera' to initialize webcam", SwingConstants.CENTER);
        cameraViewLabel.setPreferredSize(new Dimension(640, 480));
        cameraViewLabel.setBackground(new Color(10, 15, 20));
        cameraViewLabel.setOpaque(true);
        cameraViewLabel.setForeground(muted);
        cameraViewLabel.setFont(new Font("SansSerif", Font.PLAIN, 16));
        cameraViewLabel.setBorder(BorderFactory.createLineBorder(new Color(49, 68, 81), 2));

        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setBackground(panel);
        controlPanel.setPreferredSize(new Dimension(320, 0));
        controlPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Camera Controls");
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        title.setForeground(green);
        controlPanel.add(title);
        controlPanel.add(Box.createVerticalStrut(15));

        JButton startBtn = new JButton("▶ Start Live Camera");
        startBtn.setMaximumSize(new Dimension(280, 42));
        startBtn.setBackground(green);
        startBtn.setForeground(new Color(16, 24, 32));
        startBtn.setOpaque(true);
        startBtn.setContentAreaFilled(true);
        startBtn.setFocusPainted(false);
        startBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        startBtn.addActionListener(e -> startCamera());

        JButton stopBtn = new JButton("⏹ Stop Camera");
        stopBtn.setMaximumSize(new Dimension(280, 42));
        stopBtn.setBackground(new Color(220, 70, 70));
        stopBtn.setForeground(white);
        stopBtn.setOpaque(true);
        stopBtn.setContentAreaFilled(true);
        stopBtn.setFocusPainted(false);
        stopBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        stopBtn.addActionListener(e -> stopCamera());

        JButton autoMarkBtn = new JButton("👤 Instant Face Recognition");
        autoMarkBtn.setMaximumSize(new Dimension(280, 42));
        autoMarkBtn.setBackground(new Color(60, 150, 220));
        autoMarkBtn.setForeground(white);
        autoMarkBtn.setOpaque(true);
        autoMarkBtn.setContentAreaFilled(true);
        autoMarkBtn.setFocusPainted(false);
        autoMarkBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        autoMarkBtn.addActionListener(e -> recognizeAndMarkAttendance());
        JLabel help = new JLabel(
            "<html><div style='width:240px; color:#a5b4c0; font-size:12px; line-height:1.5;'>" +
            "<br><b>How it works:</b><br>" +
            "1. Webcam stream is processed via JavaCV.<br>" +
            "2. OpenCV Haar Cascade detects frontal faces.<br>" +
            "3. Bounding box (green) indicates detected face.<br>" +
            "4. Detected student is logged into MySQL 'attendance' table automatically.</div></html>"
        );

        controlPanel.add(startBtn);
        controlPanel.add(Box.createVerticalStrut(10));
        controlPanel.add(stopBtn);
        controlPanel.add(Box.createVerticalStrut(10));
        controlPanel.add(autoMarkBtn);
        controlPanel.add(Box.createVerticalStrut(15));
        controlPanel.add(help);

        mainBox.add(cameraViewLabel, BorderLayout.CENTER);
        mainBox.add(controlPanel, BorderLayout.EAST);

        content.add(mainBox, BorderLayout.CENTER);
        refresh();
    }

    private void startCamera() {
        if (isCameraRunning.get()) return;

        cameraThread = new Thread(() -> {
            try {
                grabber = new OpenCVFrameGrabber(0);
                grabber.setImageWidth(640);
                grabber.setImageHeight(480);
                grabber.start();
                isCameraRunning.set(true);

                OpenCVFrameConverter.ToMat toMat = new OpenCVFrameConverter.ToMat();
                Java2DFrameConverter toBufferedImage = new Java2DFrameConverter();

                System.out.println("[+] Camera stream started.");

                while (isCameraRunning.get()) {
                    Frame frame = grabber.grab();
                    if (frame == null) continue;

                    Mat mat = toMat.convert(frame);
                    if (mat != null && !mat.empty()) {
                        // Detect faces using Haar cascade if loaded
                        if (faceDetector != null) {
                            RectVector faces = new RectVector();
                            Mat gray = new Mat();
                            cvtColor(mat, gray, COLOR_BGR2GRAY);
                            equalizeHist(gray, gray);

                            faceDetector.detectMultiScale(gray, faces);
                            long total = faces.size();

                            latestDetectedFace = null;
                            for (long i = 0; i < total; i++) {
                                Rect rect = faces.get(i);
                                if (i == 0) {
                                    Mat faceView = new Mat(gray, rect);
                                    latestDetectedFace = faceView.clone();
                                    faceView.close();
                                }
                                rectangle(mat, rect, new Scalar(60, 200, 145, 0), 2, 8, 0);
                                putText(mat, "Face Detected", new org.bytedeco.opencv.opencv_core.Point(rect.x(), Math.max(20, rect.y() - 10)),
                                        FONT_HERSHEY_SIMPLEX, 0.6, new Scalar(60, 200, 145, 0), 2, 8, false);
                            }
                        }

                        BufferedImage img = toBufferedImage.convert(toMat.convert(mat));
                        if (img != null) {
                            SwingUtilities.invokeLater(() -> {
                                if (cameraViewLabel != null) {
                                    cameraViewLabel.setIcon(new ImageIcon(img));
                                    cameraViewLabel.setText("");
                                }
                            });
                        }
                    }
                    Thread.sleep(33); // ~30 fps
                }
            } catch (Exception ex) {
                System.err.println("Camera error: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    if (cameraViewLabel != null) {
                        cameraViewLabel.setIcon(null);
                        cameraViewLabel.setText("<html><center>Camera device index 0 unavailable or in use.<br>Use 'Instant Face Recognition' button on right.</center></html>");
                    }
                });
            } finally {
                stopCamera();
            }
        });
        cameraThread.setDaemon(true);
        cameraThread.start();
    }

    private void stopCamera() {
        isCameraRunning.set(false);
        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
                grabber = null;
            }
        } catch (Exception ignored) {}

        if (cameraViewLabel != null) {
            cameraViewLabel.setIcon(null);
            cameraViewLabel.setText("Camera stopped.");
        }
    }

    private void recognizeAndMarkAttendance() {
        if (!isCameraRunning.get()) {
            JOptionPane.showMessageDialog(this, "Pehle Start Live Camera dabao.", "Camera Not Started", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Mat current = latestDetectedFace;
        if (current == null || current.empty()) {
            JOptionPane.showMessageDialog(this, "Camera mein face detect nahi hua. Camera ki taraf dekho aur dobara try karo.", "Face Not Detected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            File root = new File("face_data");
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs == null) throw new Exception("face_data folder nahi mila.");
            java.util.List<Mat> images = new ArrayList<>();
            java.util.List<Integer> labelList = new ArrayList<>();
            Map<Integer, String> rollByLabel = new HashMap<>();
            int label = 0;
            for (File dir : dirs) {
                File[] pics = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().matches(".*\\.(png|jpg|jpeg)$"));
                if (pics == null || pics.length == 0) continue;
                boolean used = false;
                for (File pic : pics) {
                    Mat img = imread(pic.getAbsolutePath(), IMREAD_GRAYSCALE);
                    if (img == null || img.empty()) { if (img != null) img.close(); continue; }
                    Mat normalized = new Mat();
                    org.bytedeco.opencv.global.opencv_imgproc.resize(img, normalized, new org.bytedeco.opencv.opencv_core.Size(100, 100));
                    img.close();
                    images.add(normalized);
                    labelList.add(label);
                    used = true;
                }
                if (used) { rollByLabel.put(label, dir.getName()); label++; }
            }
            if (images.size() < 2 || rollByLabel.size() < 2) {
                for (Mat img : images) img.close();
                throw new Exception("Kam se kam 2 students ke saved face samples chahiye. face_data folder check karo.");
            }
            MatVector trainingImages = new MatVector(images.size());
            Mat labels = new Mat(images.size(), 1, CV_32SC1);
            IntPointer labelPtr = new IntPointer(labels.data());
            for (int i = 0; i < images.size(); i++) {
                trainingImages.put(i, images.get(i));
                labelPtr.put(i, labelList.get(i));
            }
            LBPHFaceRecognizer model = LBPHFaceRecognizer.create(1, 8, 8, 8, 75.0);
            model.train(trainingImages, labels);
            Mat query = new Mat();
            org.bytedeco.opencv.global.opencv_imgproc.resize(current, query, new org.bytedeco.opencv.opencv_core.Size(100, 100));
            IntPointer predicted = new IntPointer(1);
            DoublePointer confidence = new DoublePointer(1);
            model.predict(query, predicted, confidence);
            int foundLabel = predicted.get(0);
            double score = confidence.get(0);
            String roll = rollByLabel.get(foundLabel);
            query.close(); labels.close(); model.close();
            for (Mat img : images) img.close();
            if (roll == null || score > 75.0) {
                JOptionPane.showMessageDialog(this, String.format("Unknown face. Match reliable nahi hai (score %.1f). Attendance mark nahi hui.", score), "Unknown Face", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Object[][] students = studentDAO.getAllStudents();
            String studentName = roll;
            for (Object[] row : students) {
                if (row.length > 1 && roll.equals(String.valueOf(row[1]))) { studentName = String.valueOf(row[2]); break; }
            }
            String attendanceResult = attendanceDAO.processFaceRecognitionAttendance(roll);
            if ("CHECKED_IN".equals(attendanceResult)) {
                lastRecognizedRoll = roll;
                lastAttendanceMillis = System.currentTimeMillis();
                refresh();
                JOptionPane.showMessageDialog(this,
                        String.format("Recognized: %s\\nRoll: %s\\nCheck-in saved. Status: Pending.\\nPresent will be marked only after face checkout (40+ minutes).", studentName, roll),
                        "Checked In", JOptionPane.INFORMATION_MESSAGE);
            } else if (attendanceResult.startsWith("ALREADY_CHECKED_IN:")) {
                String remaining = attendanceResult.substring("ALREADY_CHECKED_IN:".length());
                JOptionPane.showMessageDialog(this,
                        "Recognized: " + studentName + " (" + roll + ").\\nAlready checked in. Check-out is allowed after 40 minutes.\\nApprox. minutes remaining: " + remaining,
                        "Already Checked In", JOptionPane.INFORMATION_MESSAGE);
            } else if (attendanceResult.startsWith("CHECKED_OUT:")) {
                String checkoutTime = attendanceResult.substring("CHECKED_OUT:".length());
                refresh();
                JOptionPane.showMessageDialog(this,
                        "Recognized: " + studentName + " (" + roll + ").\\nCheck-out successful at: " + checkoutTime + "\\nAttendance status: Present",
                        "Checked Out", JOptionPane.INFORMATION_MESSAGE);
            } else if (attendanceResult.startsWith("ALREADY_CHECKED_OUT:")) {
                String checkoutTime = attendanceResult.substring("ALREADY_CHECKED_OUT:".length());
                JOptionPane.showMessageDialog(this,
                        "Recognized: " + studentName + " (" + roll + ").\\nAlready checked out at: " + checkoutTime,
                        "Already Checked Out", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Attendance process failed: " + attendanceResult,
                        "Database Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Recognition failed: " + ex.getMessage() + "\\nCMD output bhi check karo.", "Recognition Error", JOptionPane.ERROR_MESSAGE);
        }
    }


    private void showAbout() {
        preparePage("About the Project", "Java-based Face Recognition Attendance System");

        JTextArea about = new JTextArea(
            "==========================================================\n" +
            "  FACE RECOGNITION ATTENDANCE MANAGEMENT SYSTEM\n" +
            "==========================================================\n\n" +
            "Features Implemented:\n" +
            "✔ Java Swing Dark Dashboard UI\n" +
            "✔ MySQL Database Connectivity via JDBC (face_attendance)\n" +
            "✔ OpenCV 4.9.0 & JavaCV 1.5.10 Integration\n" +
            "✔ Haar Cascade Face Detection Algorithm\n" +
            "✔ Real-time Student Management (Add, View, Live Count)\n" +
            "✔ Real-time Attendance Marking & Statistics\n\n" +
            "Database Schema:\n" +
            "- students (student_id, roll_no, name, department)\n" +
            "- attendance (attendance_id, student_id, attendance_date, check_in_time, check_out_time, status)\n\n" +
            "Author: Harsh Chib\n" +
            "Status: Check-in/checkout integration requires testing"
        );

        about.setEditable(false);
        about.setLineWrap(true);
        about.setWrapStyleWord(true);
        about.setFont(new Font("SansSerif", Font.PLAIN, 15));
        about.setForeground(white);
        about.setBackground(panel);
        about.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        content.add(about, BorderLayout.CENTER);
        refresh();
    }

    private JTable createTable(String[] columns, Object[][] data) {
        JTable table = new JTable(new DefaultTableModel(data, columns));
        styleTable(table);
        return table;
    }

    private void styleTable(JTable table) {
        table.setRowHeight(34);
        table.setBackground(panel);
        table.setForeground(white);
        table.setGridColor(new Color(49, 68, 81));
        table.setSelectionBackground(new Color(35, 80, 72));
        table.setSelectionForeground(white);
        table.getTableHeader().setBackground(new Color(35, 59, 70));
        table.getTableHeader().setForeground(white);
        table.getTableHeader().setOpaque(true);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 13));
        javax.swing.table.DefaultTableCellRenderer headerRenderer =
                new javax.swing.table.DefaultTableCellRenderer();
        headerRenderer.setOpaque(true);
        headerRenderer.setBackground(new Color(35, 59, 70));
        headerRenderer.setForeground(Color.WHITE);
        headerRenderer.setFont(new Font("SansSerif", Font.BOLD, 13));
        headerRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        table.getTableHeader().setDefaultRenderer(headerRenderer);
    }

    private void refresh() {
        content.revalidate();
        content.repaint();
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(FaceAttendanceDashboard::new);
    }
}

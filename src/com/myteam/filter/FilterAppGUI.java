package com.myteam.filter;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class FilterAppGUI extends JFrame {
    // ── UI COMPONENTS ─────────────────────────────────────────────
    private final JRadioButton imgRadio, vidRadio;
    private final JTextField inField, outField;
    private final JButton inBtn, outBtn, runBtn, stopBtn, stopVideoBtn;
    private final JComboBox<ImageFilter.FilterType> filterCombo;
    private final JLabel kernelLbl, origLbl, seqLbl, parLbl;
    private final JComboBox<Integer> kernelCombo;
    private final DefaultTableModel tableModel;
    private SwingWorker<?,?> worker;

    // ── STORED IMAGES FOR RESCALING ────────────────────────────────
    private BufferedImage origImg, seqImg, parImg;

    // ── VIDEO LOOPER ──────────────────────────────────────────────
    private VideoLooper looper;
    private Thread looperThread;

    public FilterAppGUI() {
        super("FilterApp");

        // ── BUMP UI FONTS ──────────────────────────────────────────
        Font base = new Font("SansSerif", Font.PLAIN, 16);
        UIManager.put("Label.font",        base);
        UIManager.put("Button.font",       base);
        UIManager.put("TextField.font",    base);
        UIManager.put("ComboBox.font",     base);
        UIManager.put("RadioButton.font",  base);
        UIManager.put("TitledBorder.font", base.deriveFont(Font.BOLD,18f));
        UIManager.put("Table.font",        base.deriveFont(16f));
        UIManager.put("TableHeader.font",  base.deriveFont(Font.BOLD,16f));
        // ─────────────────────────────────────────────────────────────

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8,8));
        ((JComponent)getContentPane()).setBorder(new EmptyBorder(8,8,8,8));

        // ── SETTINGS PANEL ─────────────────────────────────────────
        JPanel settings = new JPanel(new GridBagLayout());
        settings.setBorder(new TitledBorder("Settings"));
        settings.setPreferredSize(new Dimension(700,180));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4,4,4,4);
        gbc.anchor = GridBagConstraints.WEST;

        // Mode
        gbc.gridx=0; gbc.gridy=0;
        settings.add(new JLabel("Mode:"), gbc);
        imgRadio = new JRadioButton("Image", true);
        vidRadio = new JRadioButton("Video");
        ButtonGroup bgGroup = new ButtonGroup();
        bgGroup.add(imgRadio);
        bgGroup.add(vidRadio);
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT,2,0));
        modePanel.add(imgRadio);
        modePanel.add(vidRadio);
        gbc.gridx=1; gbc.gridwidth=2;
        settings.add(modePanel, gbc);
        gbc.gridwidth=1;

        // Input File
        gbc.gridy=1; gbc.gridx=0;
        settings.add(new JLabel("Input File:"), gbc);
        inField = new JTextField();
        inBtn   = new JButton("Browse…");
        JPanel inPanel = new JPanel(new BorderLayout(4,0));
        inPanel.add(inField, BorderLayout.CENTER);
        inPanel.add(inBtn,    BorderLayout.EAST);
        gbc.gridx=1; gbc.gridwidth=2;
        gbc.fill=GridBagConstraints.HORIZONTAL; gbc.weightx=1;
        settings.add(inPanel, gbc);
        gbc.gridwidth=1; gbc.fill=GridBagConstraints.NONE; gbc.weightx=0;

        // Output Base
        gbc.gridy=2; gbc.gridx=0;
        settings.add(new JLabel("Output Base:"), gbc);
        outField = new JTextField();
        outBtn   = new JButton("Browse…");
        JPanel outPanel = new JPanel(new BorderLayout(4,0));
        outPanel.add(outField, BorderLayout.CENTER);
        outPanel.add(outBtn,    BorderLayout.EAST);
        gbc.gridx=1; gbc.gridwidth=2;
        gbc.fill=GridBagConstraints.HORIZONTAL; gbc.weightx=1;
        settings.add(outPanel, gbc);
        gbc.gridwidth=1; gbc.fill=GridBagConstraints.NONE; gbc.weightx=0;

        // Filter + Kernel + Run/Stop + Stop Video
        gbc.gridy=3; gbc.gridx=0;
        settings.add(new JLabel("Filter:"), gbc);
        filterCombo = new JComboBox<>(ImageFilter.FilterType.values());
        filterCombo.setSelectedItem(ImageFilter.FilterType.GAUSSIAN);
        kernelLbl   = new JLabel("Kernel:");
        kernelCombo = new JComboBox<>(new Integer[]{3,5,7,9,11});
        kernelCombo.setSelectedItem(9);
        runBtn       = new JButton("Run Filter");
        stopBtn      = new JButton("Stop");
        stopVideoBtn = new JButton("Stop Video");
        stopBtn.setEnabled(false);
        stopVideoBtn.setEnabled(false);
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        filterPanel.add(filterCombo);
        filterPanel.add(kernelLbl);
        filterPanel.add(kernelCombo);
        filterPanel.add(runBtn);
        filterPanel.add(stopBtn);
        filterPanel.add(stopVideoBtn);
        gbc.gridx=1; gbc.gridwidth=2;
        settings.add(filterPanel, gbc);
        gbc.gridwidth=1;
        // ─────────────────────────────────────────────────────────────

        // ── RESULTS TABLE ───────────────────────────────────────────
        String[] cols = {"Metric","Sequential","Parallel"};
        Object[][] data = {
            {"Time (s)", "" ,""},
            {"CPU (%)",  "" ,""},
            {"RAM (MB)", "" ,""},
            {"Speedup",  "" ,""}
        };
        tableModel = new DefaultTableModel(data, cols) {
            @Override public boolean isCellEditable(int r,int c){return false;}
        };
        JTable resultsTable = new JTable(tableModel);
        resultsTable.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(resultsTable);
        tableScroll.setBorder(new TitledBorder("Results"));
        tableScroll.setPreferredSize(new Dimension(300,180));
        // ─────────────────────────────────────────────────────────────

        // ── TOP SPLIT ──────────────────────────────────────────────
        JSplitPane topSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            settings,
            tableScroll
        );
        topSplit.setResizeWeight(0.66);
        topSplit.setOneTouchExpandable(true);
        add(topSplit, BorderLayout.NORTH);
        // ─────────────────────────────────────────────────────────────

        // ── PREVIEW PANEL ───────────────────────────────────────────
        JPanel preview = new JPanel(new GridLayout(1,3,6,6));
        preview.setBorder(new TitledBorder("Preview"));
        origLbl = mk("Original");
        seqLbl  = mk("Sequential");
        parLbl  = mk("Parallel");
        preview.add(origLbl);
        preview.add(seqLbl);
        preview.add(parLbl);
        add(preview, BorderLayout.CENTER);

        preview.addComponentListener(new ComponentAdapter(){
            @Override public void componentResized(ComponentEvent e){
                rescalePreviews();
            }
        });
        // ─────────────────────────────────────────────────────────────

        // ── ACTION LISTENERS ────────────────────────────────────────
        inBtn .addActionListener(e->choose(inField, imgRadio.isSelected()));
        outBtn.addActionListener(e->choose(outField, imgRadio.isSelected()));
        imgRadio.addActionListener(e->{
            filterCombo.setEnabled(true);
            kernelLbl.setVisible(true);
            kernelCombo.setVisible(true);
        });
        vidRadio.addActionListener(e->{
            filterCombo.setEnabled(true);
            kernelLbl.setVisible(true);
            kernelCombo.setVisible(true);
        });
        filterCombo.addActionListener(e->{
            boolean g = filterCombo.getSelectedItem()==ImageFilter.FilterType.GAUSSIAN;
            kernelLbl.setVisible(g);
            kernelCombo.setVisible(g);
        });
        runBtn.addActionListener(this::onRun);
        stopBtn.addActionListener(e->{
            if(worker!=null) worker.cancel(true);
            if(looper!=null) { looper.stop(); looperThread.interrupt(); looper = null; looperThread = null; }
            stopVideoBtn.setEnabled(false);
        });
        stopVideoBtn.addActionListener(e->{
            if(looper!=null) { looper.stop(); looperThread.interrupt(); looper = null; looperThread = null; }
            stopVideoBtn.setEnabled(false);
        });
        // ─────────────────────────────────────────────────────────────

        pack();
        setMinimumSize(new Dimension(800,600));
        setLocationRelativeTo(null);
    }

    /** Creates a transparent preview label with a title at its top */
    private JLabel mk(String title) {
        JLabel lbl = new JLabel(title, SwingConstants.CENTER);
        lbl.setVerticalTextPosition(SwingConstants.TOP);
        lbl.setHorizontalTextPosition(SwingConstants.CENTER);
        lbl.setIconTextGap(4);
        lbl.setOpaque(false);
        lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        return lbl;
    }

    private void choose(JTextField fld, boolean isImage) {
        JFileChooser chooser = new JFileChooser();
        if(isImage) {
            chooser.setFileFilter(new FileNameExtensionFilter(
                "Image files","png","jpg","jpeg","bmp","gif"));
        } else {
            chooser.setFileFilter(new FileNameExtensionFilter(
                "MP4 videos","mp4"));
        }
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION) {
            fld.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void onRun(ActionEvent __) {
        // Stop any existing looper
        if(looper!=null) {
            looper.stop();
            looperThread.interrupt();
            looper = null;
            looperThread = null;
        }
        stopVideoBtn.setEnabled(false);

        String in  = inField.getText().trim();
        String out = outField.getText().trim();
        ImageFilter.FilterType ft = (ImageFilter.FilterType)filterCombo.getSelectedItem();
        int k = (Integer)kernelCombo.getSelectedItem();

        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        // clear previous results & previews
        for(int r=0; r<tableModel.getRowCount(); r++){
            tableModel.setValueAt("",r,1);
            tableModel.setValueAt("",r,2);
        }
        origImg = seqImg = parImg = null;
        origLbl.setIcon(null);
        seqLbl.setIcon(null);
        parLbl.setIcon(null);

        if(imgRadio.isSelected()) {
            worker = new SwingWorker<ImageProcessor.TimingResult,Void>(){
                @Override protected ImageProcessor.TimingResult doInBackground() throws Exception {
                    origImg = ImageIO.read(new File(in));
                    ImageProcessor ip = new ImageProcessor(new ImageFilter(), ft, k);
                    String s1 = out + "_seq.png", s2 = out + "_par.png";
                    ImageProcessor.TimingResult t = ip.process(in, s1, s2);
                    seqImg = ImageIO.read(new File(s1));
                    parImg = ImageIO.read(new File(s2));
                    return t;
                }
                @Override protected void done(){
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    try {
                        ImageProcessor.TimingResult t = get();
                        tableModel.setValueAt(String.format("%.2f", t.seqSec),    0,1);
                        tableModel.setValueAt(String.format("%.2f", t.parSec),    0,2);
                        tableModel.setValueAt(String.format("%.1f", t.seqCpuMaxPct),1,1);
                        tableModel.setValueAt(String.format("%.1f", t.parCpuMaxPct),1,2);
                        tableModel.setValueAt(String.format("%.1f", t.seqRamMB),   2,1);
                        tableModel.setValueAt(String.format("%.1f", t.parRamMB),   2,2);
                        tableModel.setValueAt(String.format("%.2fx",t.speedup()),  3,1);
                        rescalePreviews();
                    } catch(Exception ex){
                        JOptionPane.showMessageDialog(
                            FilterAppGUI.this,
                            ex.getCause().getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        );
                    }
                }
            };
            worker.execute();
        } else {
            worker = new SwingWorker<VideoProcessor.TimingResult,Void>(){
                @Override protected VideoProcessor.TimingResult doInBackground() throws Exception {
                    VideoProcessor vp = new VideoProcessor(new ImageFilter(), ft, k);
                    String seqPath = out + "_seq.mp4", parPath = out + "_par.mp4";
                    return vp.process(in, seqPath, parPath);
                }
                @Override protected void done(){
                    runBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    try {
                        VideoProcessor.TimingResult t = get();
                        tableModel.setValueAt(String.format("%.2f", t.seqSec),    0,1);
                        tableModel.setValueAt(String.format("%.2f", t.parSec),    0,2);
                        tableModel.setValueAt(String.format("%.1f", t.seqCpuMaxPct),1,1);
                        tableModel.setValueAt(String.format("%.1f", t.parCpuMaxPct),1,2);
                        tableModel.setValueAt(String.format("%.1f", t.seqRamMB),   2,1);
                        tableModel.setValueAt(String.format("%.1f", t.parRamMB),   2,2);
                        tableModel.setValueAt(String.format("%.2fx",t.speedup()),  3,1);

                        // start looping playback
                        looper = new VideoLooper(new String[]{ in, out + "_seq.mp4", out + "_par.mp4" });
                        looperThread = new Thread(looper);
                        looperThread.start();
                        stopVideoBtn.setEnabled(true);
                    } catch(Exception ex){
                        JOptionPane.showMessageDialog(
                            FilterAppGUI.this,
                            ex.getCause().getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        );
                    }
                }
            };
            worker.execute();
        }
    }

    /** Rescale whichever preview frames are defined */
    private void rescalePreviews(){
        if(origImg!=null) origLbl.setIcon(fit(origImg, origLbl));
        if(seqImg !=null) seqLbl .setIcon(fit(seqImg,   seqLbl));
        if(parImg !=null) parLbl .setIcon(fit(parImg,   parLbl));
    }

    /**
     * Scale `img` to fit inside the label’s width and
     * inside the label’s height minus text + gap + padding.
     */
    private Icon fit(BufferedImage img, JLabel lbl){
        int w = lbl.getWidth(), h = lbl.getHeight();
        if(w<=0||h<=0) return null;
        FontMetrics fm = lbl.getFontMetrics(lbl.getFont());
        int textH = fm.getHeight(), gap = lbl.getIconTextGap();
        int availH = h - textH - gap - 4;
        if(availH<=0) return null;
        double ar = img.getWidth()/(double)img.getHeight();
        int finalW = w, finalH = (int)(finalW/ar);
        if(finalH>availH){
            finalH = availH;
            finalW = (int)(availH*ar);
        }
        Image scaled = img.getScaledInstance(finalW, finalH, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    /** Runnable that loops through videos and updates the respective label continuously */
    private class VideoLooper implements Runnable {
        private final String[] paths;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Java2DFrameConverter conv = new Java2DFrameConverter();

        public VideoLooper(String[] paths) {
            this.paths = paths;
        }

        public void stop() {
            running.set(false);
        }

        @Override
        public void run() {
            while (running.get()) {
                for (int i = 0; i < paths.length && running.get(); i++) {
                    String path = paths[i];
                    try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path)) {
                        grabber.start();
                        Frame frame;
                        while (running.get() && (frame = grabber.grabImage()) != null) {
                            BufferedImage img = conv.convert(frame);
                            final BufferedImage imgCopy = img;
                            JLabel target = (i == 0 ? origLbl : i == 1 ? seqLbl : parLbl);
                            SwingUtilities.invokeLater(() -> {
                                target.setIcon(fit(imgCopy, target));
                            });
                            Thread.sleep(33); // ~30fps
                        }
                        grabber.stop();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FilterAppGUI().setVisible(true));
    }
}

package org.srwiki.pirate;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Plot;
import ij.plugin.filter.PlugInFilter;
import ij.process.ByteProcessor;
import ij.process.FHT;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Component;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicScrollBarUI;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PIRATE_Normalization implements PlugInFilter {

    private ImagePlus imp;
    private BufferedImage pirateLogoImage;

    // 更改了插件名称以匹配新的定位
    private static final String PLUGIN_NAME    = "Quantitative Fluorescence Standardizer";
    private static final String PLUGIN_VERSION = "v1.0";
    private static final String HELP_URL       = "https://github.com/SR-Wiki/PIRATE";
    // Logo asset. Place this PNG at the root of the plugin classpath, e.g. src/main/resources/pirate_logo_3x4_transparent.png
    private static final String PIRATE_LOGO_RESOURCE = "/pirate_logo_3x4_transparent.png";
    private static final String PIRATE_LOGO_RESOURCE_ALT = "/org/srwiki/pirate/pirate_logo_3x4_transparent.png";

    // ── PIRATE hyper-parameters ─────────────────────────────────────────────
    private static final int    WINDOW_SIZE      = 64;
    private static final int    STEP_SIZE        = 32;
    private static final double STD_MULT         = 0.7;
    private static final double KURT_MULT        = 2.0;
    private static final int    MAX_BG_BLOCKS    = 100;
    private static final double SIGNAL_PERCENTILE = 99.9;

    // ── Dark Mode Palette (Professional Microscopy Theme) ──────────────────
    private static final Color BG_DEEP      = new Color(15, 23, 42);   // 深板岩蓝（底色）
    private static final Color BG_CARD      = new Color(30, 41, 59);   // 略浅的卡片色
    private static final Color BG_SIDEBAR   = new Color(15, 23, 42);   // 侧边栏背景
    private static final Color BG_ITEM      = new Color(51, 65, 85, 100); 
    private static final Color BG_ITEM_HOV  = new Color(13, 148, 136, 40);
    private static final Color BORDER_DIM   = new Color(51, 65, 85);   // 边框
    private static final Color BORDER_MED   = new Color(71, 85, 105);
    private static final Color ACCENT_TEAL  = new Color(45, 212, 191); // 荧光绿
    private static final Color ACCENT_BLUE  = new Color(96, 165, 250); // 激光蓝
    private static final Color TEXT_BRIGHT  = new Color(241, 245, 249);
    private static final Color TEXT_MED     = new Color(148, 163, 184);
    private static final Color TEXT_DIM     = new Color(255, 255, 255);

    // ── Enums ────────────────────────────────────────────────────────────────
    private enum NormalizationMode {
        MAX_ONLY   ("Max normalization"),
        MIN_MAX    ("Min-Max normalization"),
        PERCENTILE ("Percentile normalization"),
        PIRATE     ("PIRATE");

        final String label;
        NormalizationMode(String label) { this.label = label; }
    }

    // ── Config / Model helpers ───────────────────────────────────────────────
    private static class LaunchConfig {
        NormalizationMode mode       = NormalizationMode.PIRATE;
        boolean showHistogram        = true;
        double  lowPercentile        = 1.0;
        double  highPercentile       = 99.8;
    }

    private static class ComputationModel {
        NormalizationMode mode;
        String            methodName;
        String            summary;
        double xMin, xMax, pMin, pMax;
    }

    private class Block implements Comparable<Block> {
        int    x, y;
        float  freqStd, grayStd, kurtosis;
        double bgScore;

        Block(int x, int y, float freqStd, float grayStd, float kurtosis) {
            this.x = x; this.y = y;
            this.freqStd = freqStd; this.grayStd = grayStd; this.kurtosis = kurtosis;
        }
        @Override public int compareTo(Block o) { return Double.compare(bgScore, o.bgScore); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PlugInFilter entry points
    // ════════════════════════════════════════════════════════════════════════
    @Override
    public int setup(String arg, ImagePlus imp) {
        this.imp = imp;
        return DOES_8G | DOES_16 | DOES_32 | NO_CHANGES;
    }

    @Override
    public void run(ImageProcessor ip) {
        if (imp == null) { IJ.error(PLUGIN_NAME, "No image is open."); return; }

        boolean isStack = imp.getStackSize() > 1;

        ByteProcessor reference8bit = convertToWorking8Bit(
                isStack ? imp.getStack().getProcessor(1) : imp.getProcessor());
        if (reference8bit == null) {
            IJ.error(PLUGIN_NAME, "Unable to prepare 8-bit working image.");
            return;
        }

        LaunchConfig config = showPIRATEDialog(reference8bit);
        if (config == null) return; 

        ComputationModel model = buildComputationModel(reference8bit, config);
        if (model == null) {
            IJ.error(PLUGIN_NAME, "Normalization model could not be computed.");
            return;
        }

        logRunInfo(model, imp.getBitDepth(), isStack);

        if (!isStack && config.showHistogram) {
            drawHistogram(reference8bit, model);
        }

        if (isStack) processStack(model);
        else         processSingle(reference8bit, model);

        IJ.showStatus(PLUGIN_NAME + ": done.");
    }

    private ByteProcessor convertToWorking8Bit(ImageProcessor src) {
        try { 
            ByteProcessor bp = (ByteProcessor) src.convertToByte(true);
            bp.setColorModel(src.getColorModel()); 
            return bp;
        } 
        catch (Exception e) { return null; }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Processing
    // ════════════════════════════════════════════════════════════════════════
    private void processSingle(ByteProcessor source8bit, ComputationModel model) {
        ImageProcessor result = applyModel(source8bit, model);
        ImagePlus outImp = new ImagePlus(buildOutputTitle(imp.getTitle(), model), result);
        outImp.show();
    }

    private void processStack(ComputationModel model) {
        ImageStack old   = imp.getStack();
        int w = old.getWidth(), h = old.getHeight(), n = old.getSize();
        ImageStack newStack = new ImageStack(w, h);
        
        newStack.setColorModel(old.getColorModel());

        for (int s = 1; s <= n; s++) {
            IJ.showProgress(s, n);
            ByteProcessor slice8bit = convertToWorking8Bit(old.getProcessor(s));
            newStack.addSlice(old.getSliceLabel(s), applyModel(slice8bit, model));
        }
        ImagePlus outImp = new ImagePlus(buildOutputTitle(imp.getTitle(), model), newStack);
        outImp.show();
    }

    private ImageProcessor applyModel(ByteProcessor src, ComputationModel model) {
        return applyLinearNormalization(src, model.xMin, model.xMax);
    }

    private String buildOutputTitle(String base, ComputationModel model) {
        return String.format("%s_%s_Pmin%.2f_Pmax%.2f", base, model.methodName, model.pMin, model.pMax);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Model builders
    // ════════════════════════════════════════════════════════════════════════
    private ComputationModel buildComputationModel(ByteProcessor ref, LaunchConfig cfg) {
        switch (cfg.mode) {
            case MAX_ONLY:   return buildMaxModel(ref);
            case MIN_MAX:    return buildMinMaxModel(ref);
            case PERCENTILE: return buildPercentileModel(ref, cfg.lowPercentile, cfg.highPercentile);
            case PIRATE:     return buildPIRATEModel(ref);
            default:         return buildPIRATEModel(ref);
        }
    }

    private ComputationModel buildMaxModel(ByteProcessor ip) {
        int maxVal = getOccupiedMax(ip);
        if (maxVal <= 0) maxVal = 255;
        ComputationModel m = new ComputationModel();
        m.mode = NormalizationMode.MAX_ONLY;
        m.methodName = "Max"; m.xMin = 0; m.xMax = maxVal;
        m.pMin = 0.0; m.pMax = getRank(ip, maxVal);
        m.summary = String.format("[%s] Max normalization.", PLUGIN_NAME);
        return m;
    }

    private ComputationModel buildMinMaxModel(ByteProcessor ip) {
        int minVal = getOccupiedMin(ip), maxVal = getOccupiedMax(ip);
        if (maxVal <= minVal) { minVal = 0; maxVal = 255; }
        ComputationModel m = new ComputationModel();
        m.mode = NormalizationMode.MIN_MAX;
        m.methodName = "MinMax"; m.xMin = minVal; m.xMax = maxVal;
        m.pMin = getRank(ip, minVal); m.pMax = getRank(ip, maxVal);
        m.summary = String.format("[%s] Min-Max normalization.", PLUGIN_NAME);
        return m;
    }

    private ComputationModel buildPercentileModel(ByteProcessor ip, double lo, double hi) {
        double xMin = getPercentile(ip, lo), xMax = getPercentile(ip, hi);
        if (hi <= lo || xMax <= xMin) return buildMinMaxModel(ip);
        ComputationModel m = new ComputationModel();
        m.mode = NormalizationMode.PERCENTILE;
        m.methodName = String.format("Percentile_%.2f_%.2f", lo, hi);
        m.xMin = xMin; m.xMax = xMax;
        m.pMin = getRank(ip, xMin); m.pMax = getRank(ip, xMax);
        m.summary = String.format("[%s] Percentile normalization [%.2f%%, %.2f%%].", PLUGIN_NAME, lo, hi);
        return m;
    }

    private ComputationModel buildPIRATEModel(ByteProcessor ip) {
        FloatProcessor workFloat = (FloatProcessor) ip.convertToFloat();
        byte[] refPixels = (byte[]) ip.getPixels();
        int width = workFloat.getWidth(), height = workFloat.getHeight();

        List<Block>  allBlocks   = new ArrayList<>();
        List<Float>  allFreqStds = new ArrayList<>();
        List<Float>  allGrayStds = new ArrayList<>();

        for (int y = 0; y <= height - WINDOW_SIZE; y += STEP_SIZE) {
            for (int x = 0; x <= width - WINDOW_SIZE; x += STEP_SIZE) {
                workFloat.setRoi(x, y, WINDOW_SIZE, WINDOW_SIZE);
                ImageProcessor blockIp = workFloat.crop();
                float freqStd = calculateFreqStd(blockIp);
                float grayStd = (float) blockIp.getStatistics().stdDev;
                float kurt    = calculateKurtosis(blockIp);
                allBlocks.add(new Block(x, y, freqStd, grayStd, kurt));
                allFreqStds.add(freqStd);
                allGrayStds.add(grayStd);
            }
        }
        workFloat.resetRoi();

        if (allBlocks.isEmpty()) return buildMinMaxModel(ip);

        double minFreq = Collections.min(allFreqStds), stdFreq = getStdDev(allFreqStds);
        double minGray = Collections.min(allGrayStds), stdGray = getStdDev(allGrayStds);
        double freqThr = minFreq + STD_MULT * stdFreq;
        double grayThr = minGray + STD_MULT * stdGray;

        List<Block> potBg  = new ArrayList<>(), potSig = new ArrayList<>();
        for (Block b : allBlocks) {
            if (b.freqStd < freqThr && b.grayStd < grayThr) potBg.add(b);
            else potSig.add(b);
        }

        List<Block> finalBg = new ArrayList<>();
        if (!potBg.isEmpty()) {
            for (Block b : potBg)
                b.bgScore = 0.5 * safeRatio(b.freqStd, freqThr) + 0.5 * safeRatio(b.grayStd, grayThr);
            Collections.sort(potBg);
            for (int i = 0; i < Math.min(MAX_BG_BLOCKS, potBg.size()); i++) finalBg.add(potBg.get(i));
        }

        List<Block> finalSig = new ArrayList<>();
        if (!potSig.isEmpty()) {
            List<Float> kurtList = new ArrayList<>();
            for (Block b : potSig) kurtList.add(b.kurtosis);
            double medK = getMedian(kurtList), iqrK = getIQR(kurtList);
            double kThr = medK + KURT_MULT * iqrK;
            for (Block b : potSig) if (b.kurtosis < kThr) finalSig.add(b);
        }

        double xMin;
        if (!finalBg.isEmpty()) {
            double sum = 0; long cnt = 0;
            for (Block b : finalBg)
                for (int by = 0; by < WINDOW_SIZE; by++) {
                    int off = (b.y + by) * width + b.x;
                    for (int bx = 0; bx < WINDOW_SIZE; bx++) { sum += (refPixels[off + bx] & 0xff); cnt++; }
                }
            xMin = sum / Math.max(1, cnt);
        } else { xMin = getPercentile(ip, 1.0); }

        double xMax;
        if (!finalSig.isEmpty()) {
            int estSz = finalSig.size() * WINDOW_SIZE * WINDOW_SIZE;
            float[] sigPix = new float[estSz];
            int ptr = 0;
            for (Block b : finalSig)
                for (int by = 0; by < WINDOW_SIZE; by++) {
                    int off = (b.y + by) * width + b.x;
                    for (int bx = 0; bx < WINDOW_SIZE; bx++) sigPix[ptr++] = (refPixels[off + bx] & 0xff);
                }
            xMax = getPercentileFromFloatArray(sigPix, ptr, SIGNAL_PERCENTILE);
        } else { xMax = getPercentile(ip, SIGNAL_PERCENTILE); }

        if (xMax <= xMin) { xMin = getPercentile(ip, 1.0); xMax = getPercentile(ip, 99.0); }

        ComputationModel model = new ComputationModel();
        model.mode = NormalizationMode.PIRATE;
        model.methodName = "PIRATE"; model.xMin = xMin; model.xMax = xMax;
        model.pMin = getRank(ip, xMin); model.pMax = getRank(ip, xMax);
        model.summary = String.format("[%s] PIRATE selected %d BG and %d Signal blocks.", PLUGIN_NAME, finalBg.size(), finalSig.size());
        return model;
    }

    private ByteProcessor applyLinearNormalization(ByteProcessor src, double xMin, double xMax) {
        byte[] s = (byte[]) src.getPixels();
        ByteProcessor out = new ByteProcessor(src.getWidth(), src.getHeight());
        byte[] d = (byte[]) out.getPixels();
        double denom = xMax - xMin; if (denom <= 0) denom = 1.0;
        for (int i = 0; i < s.length; i++) {
            double n = ((s[i] & 0xff) - xMin) / denom;
            d[i] = (byte) Math.round(Math.max(0, Math.min(1, n)) * 255.0);
        }
        out.setColorModel(src.getColorModel()); 
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Logging / Histogram
    // ════════════════════════════════════════════════════════════════════════
    private void logRunInfo(ComputationModel model, int srcBitDepth, boolean isStack) {
        IJ.log("============================================================");
        IJ.log(PLUGIN_NAME + " " + PLUGIN_VERSION);
        IJ.log("Input: " + imp.getTitle() + "  [" + srcBitDepth + "-bit]");
        IJ.log("Mode: " + model.methodName);
        IJ.log(String.format("Linear limits: Xmin=%.2f (P=%.2f%%)  Xmax=%.2f (P=%.2f%%)", model.xMin, model.pMin, model.xMax, model.pMax));
    }

    private void drawHistogram(ByteProcessor ip, ComputationModel model) {
        int[] hist = ip.getHistogram();
        double total = ip.getWidth() * ip.getHeight();
        double[] x = new double[256], y = new double[256];
        double maxDensity = 0;
        for (int i = 0; i < 256; i++) { x[i] = i; y[i] = hist[i] / total; if (y[i] > maxDensity) maxDensity = y[i]; }

        Plot plot = new Plot(PLUGIN_NAME + " - 8-bit Working Histogram", "Pixel value", "Probability");
        plot.setColor(new Color(90, 130, 200)); plot.add("bar", x, y);
        double yLim = Math.max(0.01, maxDensity * 1.2);
        plot.setLimits(0, 255, 0, yLim);
        plot.setColor(new Color(255, 100, 80)); plot.setLineWidth(2);
        plot.drawLine(model.xMin, 0, model.xMin, yLim);
        plot.setColor(new Color(60, 220, 130)); plot.setLineWidth(2);
        plot.drawLine(model.xMax, 0, model.xMax, yLim);
        plot.setColor(Color.WHITE);
        plot.addLabel(0.06, 0.92, "Mode: " + model.methodName);
        plot.show();
    }

    // ════════════════════════════════════════════════════════════════════════
    // ── UI 核心与解决收缩问题的滚动面板 ─────────────────────────────────────
    // ════════════════════════════════════════════════════════════════════════

    private class ScrollablePanel extends JPanel implements Scrollable {
        public ScrollablePanel() {
            super();
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 14; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return 14; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; } 
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private LaunchConfig showPIRATEDialog(ByteProcessor refImage) {
        JDialog dialog = new JDialog(IJ.getInstance(), PLUGIN_NAME + " " + PLUGIN_VERSION, true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // 增大最小尺寸，以适配放大的字体
        dialog.setMinimumSize(new Dimension(1120, 760)); 

     // ── Root panel
        JPanel root = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setColor(BG_DEEP); 
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        root.setBorder(new LineBorder(BORDER_DIM, 1));

        // ── Header
        JButton helpBtn = makeGlassButton("Help/GitHub", new Color(45, 212, 191), 150, 36);
        helpBtn.setForeground(new Color(255, 255, 255));
        HeaderPanel header = new HeaderPanel(helpBtn);
        header.setPreferredSize(new Dimension(1120, 164));
        root.add(header, BorderLayout.NORTH);

        // ── Body
        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(16, 16, 8, 16));

        // Sidebar
        JPanel sidebar = buildSidebar();
        body.add(sidebar, BorderLayout.WEST);

        // Right pane 
        ScrollablePanel rightCol = new ScrollablePanel();

        // 1. Details card 
        JPanel detailsCard = makeCard();
        detailsCard.setLayout(new BorderLayout(0, 10));
        detailsCard.add(cardTitle("Method details"), BorderLayout.NORTH);
        JLabel detailsHtml = new JLabel(); 
        detailsHtml.setForeground(TEXT_MED);
        detailsCard.add(detailsHtml, BorderLayout.CENTER);

        // 2. Percentile params card
        JPanel paramCard = makeCard();
        paramCard.setLayout(new BorderLayout(0, 10));
        paramCard.add(cardTitle("Percentile parameters"), BorderLayout.NORTH);
        JPanel paramGrid = new JPanel(new GridBagLayout());
        paramGrid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 12); gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.HORIZONTAL;
        
        JTextField loFld = styledField("1.0");
        JTextField hiFld = styledField("99.8");
        gbc.gridx=0; gbc.gridy=0; gbc.weightx=0; paramGrid.add(fieldLabel("Lower percentile (%)"), gbc);
        gbc.gridx=1; gbc.weightx=1; paramGrid.add(loFld, gbc);
        gbc.gridx=0; gbc.gridy=1; gbc.weightx=0; paramGrid.add(fieldLabel("Upper percentile (%)"), gbc);
        gbc.gridx=1; gbc.weightx=1; paramGrid.add(hiFld, gbc);
        gbc.gridx=0; gbc.gridy=2; gbc.gridwidth=2; gbc.weightx=1; 
        paramGrid.add(makeWrappedText("Pixels outside [lower, upper] are clipped.", TEXT_DIM, new Font("SansSerif", Font.PLAIN, 13)), gbc);
        paramCard.add(paramGrid, BorderLayout.CENTER);

        // 3. Live Preview card
        JPanel previewCard = makeCard();
        previewCard.setLayout(new BorderLayout(0, 10));
        previewCard.add(cardTitle("Live Preview"), BorderLayout.NORTH);
        
        JPanel imagesContainer = new JPanel(new GridLayout(1, 2, 16, 0));
        imagesContainer.setOpaque(false);
        
        int maxThumb = 300;
        double scale = Math.min((double)maxThumb/refImage.getWidth(), (double)maxThumb/refImage.getHeight());
        if (scale > 1.0) scale = 1.0;
        int tw = (int)(refImage.getWidth()*scale), th = (int)(refImage.getHeight()*scale);
        ByteProcessor thumbSource = (ByteProcessor) refImage.resize(tw, th);
        
        thumbSource.setColorModel(refImage.getColorModel()); 
        BufferedImage imgSource = thumbSource.getBufferedImage();

        JLabel lblOrig = new JLabel(new ImageIcon(imgSource));
        lblOrig.setHorizontalAlignment(SwingConstants.CENTER);
        
        JLabel lblResult = new JLabel(new ImageIcon(imgSource)); 
        lblResult.setHorizontalAlignment(SwingConstants.CENTER);
        
        imagesContainer.add(makePreviewFrame("Raw Data", lblOrig));
        imagesContainer.add(makePreviewFrame("Processed Result", lblResult));
        previewCard.add(imagesContainer, BorderLayout.CENTER);

        // 4. Options card 
        JPanel optCard = makeCard();
        optCard.setLayout(new BorderLayout(0, 10));
        optCard.add(cardTitle("Execution options"), BorderLayout.NORTH);
        
        JPanel optBody = new JPanel(); 
        optBody.setOpaque(false); 
        optBody.setLayout(new BoxLayout(optBody, BoxLayout.Y_AXIS));
        
        JCheckBox showHist = styledCheckBox("Show histogram for single-image processing", true);
        showHist.setAlignmentX(Component.LEFT_ALIGNMENT);
        optBody.add(showHist);
        
        optCard.add(optBody, BorderLayout.CENTER);

        rightCol.add(previewCard);
        rightCol.add(Box.createVerticalStrut(12));
        rightCol.add(detailsCard);
        rightCol.add(Box.createVerticalStrut(12));
        rightCol.add(paramCard);
        rightCol.add(Box.createVerticalStrut(12));
        rightCol.add(optCard);

        JScrollPane rightScroll = makeScrollPane(rightCol);
        body.add(rightScroll, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        // ── Footer (在此处加入左下角作者信息) ──
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(12, 16, 16, 16));
        
        // 左侧的作者信息
        JLabel authorLabel = new JLabel("Author: IPIC");
        authorLabel.setForeground(TEXT_MED); // 灰蓝色，低调专业
        authorLabel.setFont(new Font("SansSerif", Font.ITALIC, 15)); // 斜体
        
        // 右侧的按钮容器
        JPanel footBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        footBtns.setOpaque(false);
        JButton cancelBtn = makeGlassButton("Cancel", ACCENT_TEAL, 110, 40);
        cancelBtn.setForeground(Color.WHITE);
        JButton runBtn = makeGlassButton("▶  START PROCESS", ACCENT_TEAL, 180, 42);
        runBtn.setForeground(Color.WHITE);
        footBtns.add(cancelBtn); 
        footBtns.add(runBtn);
        
        footer.add(authorLabel, BorderLayout.WEST);  // 放置在整个窗口左下角
        footer.add(footBtns, BorderLayout.EAST);     // 按钮放在右侧
        root.add(footer, BorderLayout.SOUTH);

        // ── Logic Wire Up ──
        JRadioButton rbMax  = (JRadioButton) sidebar.getClientProperty("rbMax");
        JRadioButton rbMM   = (JRadioButton) sidebar.getClientProperty("rbMinMax");
        JRadioButton rbPct  = (JRadioButton) sidebar.getClientProperty("rbPercentile");
        JRadioButton rbPir  = (JRadioButton) sidebar.getClientProperty("rbPIRATE");
        final LaunchConfig[] result = {null};

        Runnable updatePreview = () -> {
            NormalizationMode mode = getSelectedMode(rbMax, rbMM, rbPct, rbPir);
            LaunchConfig tempCfg = new LaunchConfig();
            tempCfg.mode = mode;
            try {
                tempCfg.lowPercentile  = Double.parseDouble(loFld.getText().trim());
                tempCfg.highPercentile = Double.parseDouble(hiFld.getText().trim());
            } catch(Exception ignored) {} 
            
            ComputationModel m = buildComputationModel(refImage, tempCfg);
            ImageProcessor resThumb = applyModel(thumbSource, m);
            lblResult.setIcon(new ImageIcon(resThumb.getBufferedImage()));
        };

        Runnable refreshUI = () -> {
            NormalizationMode mode = getSelectedMode(rbMax, rbMM, rbPct, rbPir);
            detailsHtml.setText(buildSimpleHtmlFormula(mode));
            paramCard.setVisible(mode == NormalizationMode.PERCENTILE);
            rightCol.revalidate(); rightCol.repaint();
            
            if (mode == NormalizationMode.PIRATE) {
                lblResult.setIcon(new ImageIcon(imgSource)); 
                SwingUtilities.invokeLater(updatePreview);
            } else {
                updatePreview.run();
            }
        };

        for (JRadioButton rb : new JRadioButton[]{rbMax, rbMM, rbPct, rbPir})
            rb.addActionListener(e -> refreshUI.run());

        DocumentListener docList = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshUI.run(); }
            public void removeUpdate(DocumentEvent e) { refreshUI.run(); }
            public void changedUpdate(DocumentEvent e) { refreshUI.run(); }
        };
        loFld.getDocument().addDocumentListener(docList);
        hiFld.getDocument().addDocumentListener(docList);

        helpBtn.addActionListener(e -> openHelpPage());
        cancelBtn.addActionListener(e -> dialog.dispose());
        runBtn.addActionListener(e -> {
            LaunchConfig cfg = new LaunchConfig();
            cfg.mode = getSelectedMode(rbMax, rbMM, rbPct, rbPir);
            cfg.showHistogram = showHist.isSelected();
            try {
                cfg.lowPercentile  = Double.parseDouble(loFld.getText().trim());
                cfg.highPercentile = Double.parseDouble(hiFld.getText().trim());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Percentile values must be valid numbers.", PLUGIN_NAME, JOptionPane.ERROR_MESSAGE); return;
            }
            if (cfg.mode == NormalizationMode.PERCENTILE) {
                if (cfg.lowPercentile < 0 || cfg.highPercentile > 100 || cfg.highPercentile <= cfg.lowPercentile) {
                    JOptionPane.showMessageDialog(dialog, "Range must satisfy  0 ≤ lower < upper ≤ 100.", PLUGIN_NAME, JOptionPane.ERROR_MESSAGE); return;
                }
            }
            result[0] = cfg;
            dialog.dispose();
        });

        refreshUI.run();
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(IJ.getInstance());
        dialog.setVisible(true);
        return result[0];
    }

    // ── Sidebar (已清理掉 Logo 代码) ─────────────────────────────────────────
    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint bg = new GradientPaint(0, 0, BG_SIDEBAR, 0, getHeight(), new Color(10, 18, 34));
                g2.setPaint(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(new Color(45, 212, 191, 28));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            }
        };
        sidebar.setOpaque(false);
        sidebar.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER_DIM, 1, true), new EmptyBorder(16, 14, 12, 14)));
        sidebar.setPreferredSize(new Dimension(290, 520)); 
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));

        JLabel hdr = new JLabel("METHODS");
        hdr.setForeground(Color.WHITE);
        hdr.setFont(new Font("SansSerif", Font.BOLD, 20)); 
        hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(hdr); 
        sidebar.add(Box.createVerticalStrut(14));

        ButtonGroup group = new ButtonGroup();
        JRadioButton rbMax  = sidebarRadio("Max normalization",       false);
        JRadioButton rbMM   = sidebarRadio("Min-Max normalization",   false);
        JRadioButton rbPct  = sidebarRadio("Percentile normalization",false);
        JRadioButton rbPir  = sidebarRadio("PIRATE",                  true);

        for (JRadioButton rb : new JRadioButton[]{rbMax, rbMM, rbPct, rbPir}) {
            group.add(rb);
            JPanel item = sidebarItem(rb, rb == rbPir);
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            sidebar.add(item);
            sidebar.add(Box.createVerticalStrut(8));
        }
        
        // Push the decorative PIRATE logo toward the lower edge of the sidebar.
        // Keep the logo panel left-aligned so it visually follows the METHODS column.
        sidebar.add(Box.createVerticalGlue());
        JPanel logoPanel = makePirateLogoPanel();
        logoPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(logoPanel);

        sidebar.putClientProperty("rbMax", rbMax); 
        sidebar.putClientProperty("rbMinMax", rbMM);
        sidebar.putClientProperty("rbPercentile", rbPct);
        sidebar.putClientProperty("rbPIRATE", rbPir);
        return sidebar;
    }

    private JPanel sidebarItem(JRadioButton radio, boolean highlight) {
        final boolean[] hover = {false};
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean selected = radio.isSelected();
                Color fill = selected
                        ? new Color(13, 148, 136, 44)
                        : hover[0] ? new Color(45, 212, 191, 18) : new Color(30, 41, 59, 165);
                Color stroke = selected ? ACCENT_TEAL : hover[0] ? BORDER_MED : BORDER_DIM;

                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(stroke);
                g2.setStroke(new BasicStroke(selected ? 1.4f : 1.0f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                if (selected) {
                    g2.setColor(new Color(45, 212, 191, 110));
                    g2.fillRoundRect(0, 6, 3, getHeight() - 12, 3, 3);
                }
            }
        };
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        panel.setBorder(new EmptyBorder(6, 8, 6, 8));
        panel.add(radio, BorderLayout.CENTER);
        radio.addChangeListener(e -> {
            radio.setForeground(radio.isSelected() ? ACCENT_TEAL : TEXT_BRIGHT);
            panel.repaint();
        });
        panel.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover[0] = true; panel.repaint(); }
            @Override public void mouseExited(MouseEvent e)  { hover[0] = false; panel.repaint(); }
            @Override public void mouseClicked(MouseEvent e) { radio.doClick(); }
        });
        return panel;
    }

    private JRadioButton sidebarRadio(String text, boolean selected) {
        JRadioButton rb = new JRadioButton(text, selected);
        rb.setOpaque(false); 
        rb.setForeground(selected ? ACCENT_TEAL : TEXT_BRIGHT);
        rb.setFont(new Font("SansSerif", Font.PLAIN, 14)); // 字体加大 1pt
        rb.setFocusPainted(false);
        rb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return rb;
    }

    // ── Components ────────────────────────────────────────────────────────
    private JPanel makeCard() {
        JPanel card = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint bg = new GradientPaint(0, 0, BG_CARD, 0, getHeight(), new Color(24, 36, 55));
                g2.setPaint(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(new Color(45, 212, 191, 22));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(16, 16, 16, 16));
        return card;
    }

    private JLabel cardTitle(String text) {
        JLabel l = new JLabel(text); 
        l.setForeground(TEXT_BRIGHT); 
        l.setFont(new Font("SansSerif", Font.BOLD, 17)); // 从 15 放大到 17
        return l;
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text); 
        l.setForeground(TEXT_MED); 
        l.setFont(new Font("SansSerif", Font.PLAIN, 14)); // 字体加大
        return l;
    }

    private JTextField styledField(String val) {
        JTextField f = new JTextField(val); 
        f.setBackground(new Color(15, 23, 42)); // 深于卡片颜色
        f.setForeground(ACCENT_BLUE);
        f.setCaretColor(ACCENT_TEAL); 
        f.setFont(new Font("Consolas", Font.BOLD, 14));
        f.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(BORDER_MED, 1, true), new EmptyBorder(6, 8, 6, 8)));
        return f;
    }

    private JTextArea makeWrappedText(String text, Color color, Font font) {
        JTextArea ta = new JTextArea(text);
        ta.setLineWrap(true); ta.setWrapStyleWord(true);
        ta.setOpaque(false); ta.setEditable(false); ta.setFocusable(false);
        ta.setForeground(color); ta.setFont(font);
        return ta;
    }

    private JCheckBox styledCheckBox(String text, boolean selected) {
        JCheckBox cb = new JCheckBox(text, selected);
        cb.setOpaque(false); 
        cb.setForeground(TEXT_MED); 
        cb.setFont(new Font("SansSerif", Font.PLAIN, 14)); // 字体加大
        cb.setFocusPainted(false);
        return cb;
    }

    private JPanel makePreviewFrame(String title, JComponent content) {
        JPanel frame = new JPanel(new BorderLayout(0, 8)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(15, 23, 42, 145));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(new Color(45, 212, 191, 135));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            }
        };
        frame.setOpaque(false);
        frame.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(TEXT_BRIGHT);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        frame.add(titleLabel, BorderLayout.NORTH);
        frame.add(content, BorderLayout.CENTER);
        return frame;
    }

    private JPanel makePirateLogoPanel() {
        final BufferedImage logo = loadPirateLogo();
        final Rectangle visibleLogoBounds = logo == null ? null : getVisibleImageBounds(logo);
        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    int w = getWidth();
                    int h = getHeight();

                    if (logo != null && visibleLogoBounds != null) {
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        Rectangle src = visibleLogoBounds;

                        // Fine-tune the logo position here.
                        // Negative X moves the logo left; smaller bottom gap moves it closer to the lower border.
                        final int logoOffsetX = -18;
                        final int bottomGap = 2;
                        final int padX = 8;
                        final int topPad = 4;

                        double scale = Math.min((double)(w - padX * 2) / src.width,
                                (double)(h - topPad - bottomGap) / src.height);
                        int drawW = Math.max(1, (int)Math.round(src.width * scale));
                        int drawH = Math.max(1, (int)Math.round(src.height * scale));
                        int x = (w - drawW) / 2 + logoOffsetX;
                        int y = h - drawH - bottomGap;

                        // Sync the glow with the shifted logo rather than with the panel center.
                        Point2D center = new Point2D.Float(x + drawW * 0.52f, y + drawH * 0.60f);
                        float radius = Math.max(70f, Math.min(drawW, drawH) * 0.62f);
                        RadialGradientPaint glow = new RadialGradientPaint(center, radius,
                                new float[]{0.0f, 0.60f, 1.0f},
                                new Color[]{new Color(45, 212, 191, 44), new Color(59, 130, 246, 18), new Color(45, 212, 191, 0)});
                        g2.setPaint(glow);
                        g2.fillOval((int)(center.getX() - radius), (int)(center.getY() - radius),
                                (int)(radius * 2), (int)(radius * 2));

                        g2.drawImage(logo, x, y, x + drawW, y + drawH,
                                src.x, src.y, src.x + src.width, src.y + src.height, null);
                    } else {
                        drawLogoFallback(g2, w, h);
                    }
                } finally {
                    g2.dispose();
                }
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(260, 300));
        panel.setMinimumSize(new Dimension(220, 240));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
        return panel;
    }

    private BufferedImage loadPirateLogo() {
        if (pirateLogoImage != null) return pirateLogoImage;
        for (String resourcePath : new String[]{PIRATE_LOGO_RESOURCE, PIRATE_LOGO_RESOURCE_ALT}) {
            try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
                if (in != null) {
                    pirateLogoImage = ImageIO.read(in);
                    return pirateLogoImage;
                }
            } catch (Exception ignored) {
                // Logo is decorative; keep the dialog usable if the resource is missing.
            }
        }
        return null;
    }

    private Rectangle getVisibleImageBounds(BufferedImage img) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xff;
                if (alpha > 8) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return new Rectangle(0, 0, img.getWidth(), img.getHeight());
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private void drawLogoFallback(Graphics2D g2, int w, int h) {
        int bw = Math.min(w - 30, 170);
        int bh = Math.min(h - 30, 120);
        int x = (w - bw) / 2;
        int y = (h - bh) / 2 + 16;
        g2.setColor(new Color(45, 212, 191, 70));
        g2.setStroke(new BasicStroke(2f));
        g2.drawArc(x, y, bw, bh, 190, 145);
        g2.drawLine(x + bw / 2, y, x + bw / 2, y - 70);
        g2.fillPolygon(new int[]{x + bw / 2 + 4, x + bw / 2 + 62, x + bw / 2 + 4},
                new int[]{y - 66, y - 36, y - 10}, 3);
    }

    private JScrollPane makeScrollPane(JPanel panel) {
        JScrollPane sp = new JScrollPane(panel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(14);
        sp.getVerticalScrollBar().setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, Integer.MAX_VALUE));
        sp.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                thumbColor = new Color(45, 212, 191, 110);
                trackColor = new Color(15, 23, 42, 0);
            }
            @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }
            private JButton zeroButton() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                b.setMinimumSize(new Dimension(0, 0));
                b.setMaximumSize(new Dimension(0, 0));
                return b;
            }
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                if (r.isEmpty() || !scrollbar.isEnabled()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(thumbColor);
                g2.fillRoundRect(r.x + 1, r.y + 2, r.width - 2, r.height - 4, 8, 8);
                g2.dispose();
            }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
                // transparent track
            }
        });
        return sp;
    }

    private JButton makeGlassButton(String text, Color accent, int w, int h) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = getModel().isPressed() ? accent.darker() : getModel().isRollover()
                        ? new Color(Math.min(accent.getRed()+20,255), Math.min(accent.getGreen()+20,255), Math.min(accent.getBlue()+20,255))
                        : new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 25);
                g2.setColor(bg); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(accent); g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8); super.paintComponent(g);
            }
        };
        btn.setContentAreaFilled(false); btn.setBorderPainted(false); btn.setForeground(accent);
        btn.setFont(new Font("SansSerif", Font.BOLD, 14)); // 按钮字体加大
        btn.setPreferredSize(new Dimension(w, h));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); btn.setFocusPainted(false);
        return btn;
    }

    private NormalizationMode getSelectedMode(JRadioButton max, JRadioButton mm, JRadioButton pct, JRadioButton pir) {
        if (max.isSelected()) return NormalizationMode.MAX_ONLY;
        if (mm.isSelected())  return NormalizationMode.MIN_MAX;
        if (pct.isSelected()) return NormalizationMode.PERCENTILE;
        return NormalizationMode.PIRATE;
    }

    private String buildSimpleHtmlFormula(NormalizationMode mode) {
        // 增大了 HTML 中标题和正文的字号，使其更加清晰
        String wrap = "<html><body style='font-family:Consolas,monospace; color:#475569; margin:0; padding:0; font-size:16pt;'>";
        String end  = "</body></html>";
        switch (mode) {
            case PIRATE:
            default:
                return wrap + "<span style='color:#ffffff; font-size:18pt;'><b>PIRATE</b></span><br><br>"
                    + "<b style='color:#f59e0b;'>Formula:</b> <span style='color:#ffffff;'> Adaptive Patch Transform Filter</span>" + end;
            case MAX_ONLY:
                return wrap + "<span style='color:#ffffff; font-size:18pt;'><b>Max Normalization</b></span><br><br>"
                        + "<b style='color:#f59e0b;'>Formula:</b> <span style='color:#ffffff;'> x' = x/max(x) ×255</span>" + end;

            case MIN_MAX:
                return wrap + "<span style='color:#ffffff; font-size:18pt;'><b>Min-Max Normalization</b></span><br><br>"
                        + "<b style='color:#f59e0b;'>Formula:</b> <span style='color:#ffffff;'> x' = (x−xmin)/(xmax−xmin) ×255</span>" + end;

            case PERCENTILE:
                return wrap + "<span style='color:#ffffff; font-size:18pt;'><b>Percentile Normalization</b></span><br><br>"
                        + "<b style='color:#f59e0b;'>Formula:</b> <span style='color:#ffffff;'> x' = clip((x−P_low)/(P_high−P_low), 0, 1) ×255</span>" + end;
        }
    }

 // ── HeaderPanel (荧光显微镜风格重绘) ──────────────────────────────────────
    private class HeaderPanel extends JPanel {
        private final JButton helpButton;
        HeaderPanel(JButton helpButton) {
            this.helpButton = helpButton; setLayout(new BorderLayout()); setOpaque(false);
            setBorder(new EmptyBorder(16, 22, 16, 22));
            buildForeground();
        }
        
        private void buildForeground() {
            JPanel topBar = new JPanel(new BorderLayout()); topBar.setOpaque(false);
            topBar.add(helpButton, BorderLayout.EAST);
            add(topBar, BorderLayout.NORTH);

            JPanel left = new JPanel(); 
            left.setOpaque(false); 
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            
            JLabel title = new JLabel(PLUGIN_NAME); 
            title.setForeground(Color.WHITE); 
            title.setFont(new Font("SansSerif", Font.BOLD, 52)); // 略微加大
            
            // 荧光渐变装饰条
            JPanel bar = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g; 
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    GradientPaint gp = new GradientPaint(0, 0, new Color(45, 212, 191), getWidth(), 0, new Color(59, 130, 246, 0));
                    g2.setPaint(gp); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 3, 3);
                }
            };
            bar.setOpaque(false); bar.setPreferredSize(new Dimension(350, 4)); bar.setMaximumSize(new Dimension(350, 4));
            
            JLabel tags = new JLabel("Quantitative Intensity Standardization for Biological Imaging");
            tags.setForeground(new Color(148, 163, 184)); 
            tags.setFont(new Font("SansSerif", Font.ITALIC, 13));
            
            left.add(Box.createVerticalGlue()); 
            left.add(title); 
            left.add(Box.createVerticalStrut(6));
            left.add(bar); 
            left.add(Box.createVerticalStrut(12)); 
            left.add(Box.createVerticalStrut(5));
            left.add(tags); 
            left.add(Box.createVerticalGlue()); 
            add(left, BorderLayout.WEST);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g); 
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            // 1. 深蓝色显微镜暗场背景
            GradientPaint bg = new GradientPaint(0, 0, new Color(15, 23, 42), w, h, new Color(30, 41, 59));
            g2.setPaint(bg); g2.fillRect(0, 0, w, h);

            // 2. 绘制装饰性“细胞结构”线 (微管风格)
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(new Color(45, 212, 191, 40)); // 半透明荧光色
            Path2D path = new Path2D.Double();
            path.moveTo(w * 0.7, 0);
            path.curveTo(w * 0.85, h * 0.3, w * 0.65, h * 0.7, w, h * 0.9);
            g2.draw(path);
            
            path.reset();
            path.moveTo(w, h * 0.2);
            path.curveTo(w * 0.8, h * 0.5, w * 0.9, h * 0.8, w * 0.7, h);
            g2.draw(path);

            // 3. 绘制发光的“荧光颗粒”
            Color[] cluster = {new Color(45, 212, 191, 80), new Color(59, 130, 246, 60), new Color(168, 85, 247, 40)};
            java.util.Random rnd = new java.util.Random(42);
            for(int i=0; i<15; i++) {
                int rx = w/2 + rnd.nextInt(w/2);
                int ry = rnd.nextInt(h);
                int size = 4 + rnd.nextInt(8);
                g2.setColor(cluster[rnd.nextInt(3)]);
                g2.fillOval(rx, ry, size, size);
            }

            // 4. 底部柔和阴影过渡
            GradientPaint shadow = new GradientPaint(0, h-40, new Color(0,0,0,0), 0, h, new Color(0,0,0,60));
            g2.setPaint(shadow); g2.fillRect(0, h-40, w, 40);
        }
    }

    private void openHelpPage() {
        try { Desktop.getDesktop().browse(new URI(HELP_URL)); } 
        catch (Exception ex) { IJ.showMessage(PLUGIN_NAME, "URL: " + HELP_URL); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Math Helpers
    // ════════════════════════════════════════════════════════════════════════
    private float calculateFreqStd(ImageProcessor ip) {
        FHT fht = new FHT(ip.convertToFloat()); fht.transform(); float[] h = (float[]) fht.getPixels();
        int w = fht.getWidth(), hh = fht.getHeight(); float[] mags = new float[w * hh];
        for (int y = 0; y < hh; y++) for (int x = 0; x < w; x++) {
            int i1 = y * w + x, i2 = ((hh - y) % hh) * w + ((w - x) % w);
            mags[i1] = (float) Math.sqrt((h[i1]*h[i1] + h[i2]*h[i2]) / 2.0);
        }
        return (float) getStdDevPix(mags);
    }
    private double getStdDevPix(float[] p) {
        double s = 0, s2 = 0; int n = p.length; for (float v : p) { s += v; s2 += v * v; }
        double mean = s / n; return Math.sqrt(s2 / n - mean * mean);
    }
    private float calculateKurtosis(ImageProcessor ip) {
        float[] pix = (float[]) ip.convertToFloat().getPixels(); int n = pix.length; double s = 0, s2 = 0;
        for (float v : pix) { s += v; s2 += v * v; }
        double mean = s / n, std = Math.sqrt(s2 / n - mean * mean); if (std == 0) return 0;
        double s4 = 0; for (float v : pix) s4 += Math.pow((v - mean) / std, 4); return (float)(s4 / n);
    }
    private double getStdDev(List<Float> list) {
        double s = 0; for (float v : list) s += v; double mean = s / list.size(); double sq = 0;
        for (float v : list) sq += (v - mean) * (v - mean); return Math.sqrt(sq / list.size());
    }
    private double getMedian(List<Float> list) {
        List<Float> copy = new ArrayList<>(list); Collections.sort(copy); int n = copy.size();
        return (n % 2 == 0) ? (copy.get(n/2-1) + copy.get(n/2)) / 2.0 : copy.get(n/2);
    }
    private double getIQR(List<Float> list) {
        List<Float> copy = new ArrayList<>(list); Collections.sort(copy); int n = copy.size();
        return copy.get((int)(n * 0.75)) - copy.get((int)(n * 0.25));
    }
    private int getOccupiedMin(ByteProcessor ip) { int[] h = ip.getHistogram(); int m = 0; while (m < 255 && h[m] == 0) m++; return m; }
    private int getOccupiedMax(ByteProcessor ip) { int[] h = ip.getHistogram(); int m = 255; while (m > 0 && h[m] == 0) m--; return m; }
    private double getPercentile(ByteProcessor ip, double p) {
        int[] h = ip.getHistogram(); int target = (int) Math.round(ip.getPixelCount() * p / 100.0), sum = 0;
        for (int i = 0; i < 256; i++) { sum += h[i]; if (sum >= target) return i; } return 255;
    }
    private double getRank(ByteProcessor ip, double value) {
        int[] h = ip.getHistogram(); int lim = (int) Math.min(255, Math.max(0, Math.round(value))), cnt = 0;
        for (int i = 0; i <= lim; i++) cnt += h[i]; return (double) cnt / Math.max(1, ip.getPixelCount()) * 100.0;
    }
    private double getPercentileFromFloatArray(float[] arr, int count, double p) {
        if (count <= 0) return 0; Arrays.sort(arr, 0, count);
        return arr[Math.max(0, Math.min((int) Math.round(p / 100.0 * (count - 1)), count - 1))];
    }
    private double safeRatio(double a, double b) { return b == 0 ? 0 : a / b; }
}

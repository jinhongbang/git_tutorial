/*
 * Copyright (C) 2023 たんらる
 */

package jp.fourthline.mabiicco.ui.editor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;

import jp.fourthline.mabiicco.AppResource;
import jp.fourthline.mabiicco.ui.IMMLManager;
import jp.fourthline.mabiicco.ui.PianoRollView;
import jp.fourthline.mmlTools.MMLBuilder;
import jp.fourthline.mmlTools.MMLEventList;
import jp.fourthline.mmlTools.MMLTempoEvent;
import jp.fourthline.mmlTools.core.MMLTokenizer;
import jp.fourthline.mmlTools.core.UndefinedTickException;
import jp.fourthline.mmlTools.optimizer.MMLStringOptimizer;
import jp.fourthline.mmlTools.parser.MMLFile;


/**
 * MMLテキストエディタ
 */
public final class MMLTextEditor implements DocumentListener, CaretListener {
	private final JDialog dialog;
	private final Window parentFrame;
	private final JPanel panel;
	private final JScrollPane scrollPane;
	private final JTextPane textPane = new JTextPane();

	private final DefaultStyledDocument doc = new DefaultStyledDocument(new StyleContext());
	private static final AttributeSet emptyStyle = createAttribute(Color.DARK_GRAY);
	private static final AttributeSet normalStyle = createAttribute(Color.BLACK);
	private static final AttributeSet tokenStyle = createAttribute(Color.BLUE);
	private static final AttributeSet commentStyle = createAttribute(Color.decode("#006400"));

	private static AttributeSet createAttribute(Color foreground) {
		var attr = new SimpleAttributeSet();
		StyleConstants.setForeground(attr, foreground);
		return attr;
	}

	// コメント文解析用
	private final List<Pattern> commentPatterns = List.of(Pattern.compile("/\\*/?([^/]|[^*]/)*\\*/"), Pattern.compile("//.*\n"));

	// もとのMMLイベントリスト: キャンセル時に使う
	private final MMLEventList originalList;

	// もとのテンポリスト: 入力時のテンポとマージして使う
	private final List<MMLTempoEvent> originalTempoList;

	private final IMMLManager mmlManager;
	private final JButton okButton = new JButton(AppResource.appText("mml.input.okButton"));
	private final JButton cancelButton = new JButton(AppResource.appText("mml.input.cancelButton"));

	private final long initialPosition;
	private final PianoRollView pianoRollView;

	public MMLTextEditor(Frame parentFrame, IMMLManager mmlManager, PianoRollView pianoRollView) throws UndefinedTickException {
		textPane.setDocument(doc);
		textPane.addCaretListener(this);
		textPane.setFont(new Font("Monospaced", Font.PLAIN, 12));
		textPane.addAncestorListener(new AncestorListener() {
			@Override
			public void ancestorRemoved(AncestorEvent event) {}

			@Override
			public void ancestorMoved(AncestorEvent event) {}

			@Override
			public void ancestorAdded(AncestorEvent event) {
				event.getComponent().requestFocusInWindow();
			}
		});
		doc.setParagraphAttributes(0, 0, emptyStyle, false);
		this.mmlManager = mmlManager;
		originalList = mmlManager.getActiveMMLPart();
		originalTempoList = new ArrayList<>(originalList.getGlobalTempoList());

		int index = mmlManager.getActiveMMLPartIndex();
		int startOffset = mmlManager.getActiveTrack().getStartOffset(index);
		var mmlBuilder = MMLBuilder.create(mmlManager.getActiveMMLPart(), startOffset);
		// テンポありの汎用出力.
		String text = new MMLStringOptimizer(mmlBuilder.toMMLString(true, false)).optimizeGen2();
		textPane.setText(text);
		applyStyle();

		this.parentFrame = parentFrame;
		this.dialog = new JDialog(parentFrame, AppResource.appText("mml.text_edit"), true);
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) { 
				cancelAction();
			}
		});
		InputMap imap = dialog.getRootPane().getInputMap(
				JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		imap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-it");
		dialog.getRootPane().getActionMap().put("close-it", new AbstractAction() {
			private static final long serialVersionUID = 4749203868495137137L;

			@Override
			public void actionPerformed(ActionEvent arg0) {
				cancelAction();
				dialog.setVisible(false);
			}});

		this.scrollPane = new JScrollPane(textPane, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		this.scrollPane.setPreferredSize(new Dimension(400, 240));

		this.panel = new JPanel(new BorderLayout());
		panel.add(scrollPane, BorderLayout.CENTER);

		okButton.addActionListener(t -> applyAction());
		okButton.addActionListener(t -> dialog.setVisible(false));
		cancelButton.addActionListener(t -> cancelAction());
		cancelButton.addActionListener(t -> dialog.setVisible(false));

		JPanel buttonPanel = new JPanel();
		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);
		panel.add(buttonPanel, BorderLayout.SOUTH);

		doc.addDocumentListener(this);

		this.pianoRollView = pianoRollView;
		initialPosition = (pianoRollView != null) ? pianoRollView.getSequencePosition() : 0;
	}

	private void applyStyle() {
		try {
			String text = doc.getText(0, doc.getLength());
			doc.setCharacterAttributes(0, text.length(), emptyStyle, true);
			MMLTokenizer tokenizer = new MMLTokenizer(text);
			while (tokenizer.hasNext()) {
				String s = tokenizer.next();
				int i[] = tokenizer.getIndex();
				var style = MMLTokenizer.isNote(s.charAt(0)) ? normalStyle : tokenStyle;
				doc.setCharacterAttributes(i[0], 1, style, true);
			}

			// コメントのスタイル設定
			commentPatterns.forEach(ptn -> {
				Matcher m = ptn.matcher(text + '\n');
				for (int i = 0; m.find(i); i = m.end()) {
					int start = m.start();
					int end = m.end();
					doc.setCharacterAttributes(start, end-start, commentStyle, true);
				}
			});

			setMML(text);
			if (parentFrame != null) {
				parentFrame.repaint();
			}
		} catch (BadLocationException e) {}
	}

	public void setMML(String text) {
		text = MMLFile.toMMLText(text);
		boolean allow = true;
		int index = mmlManager.getActiveMMLPartIndex();
		int startOffset = mmlManager.getActiveTrack().getStartOffset(index);
		var eventList = new MMLEventList(text, null, startOffset); // グローバルテンポリストから切り離す
		try {
			eventList.getInternalMMLString();
		} catch (UndefinedTickException e) {
			allow = false;
		}
		mmlManager.getActiveTrack().getMMLEventList().set(index, eventList);
		var tempoList = mmlManager.getMMLScore().getTempoEventList();
		tempoList.clear();
		MMLTempoEvent.mergeTempoList(originalTempoList, tempoList);
		MMLTempoEvent.mergeTempoList(eventList.getGlobalTempoList(), tempoList);

		okButton.setEnabled(allow);
	}

	public void cancelAction() {
		int index = mmlManager.getActiveMMLPartIndex();
		mmlManager.getActiveTrack().getMMLEventList().set(index, originalList);
		var tempoList = mmlManager.getMMLScore().getTempoEventList();
		tempoList.clear();
		tempoList.addAll(originalTempoList);
		if (parentFrame != null) {
			parentFrame.repaint();
		}
		if (pianoRollView != null) {
			pianoRollView.setSequenceTick(initialPosition);
		}
	}

	public void applyAction() {
		int index = mmlManager.getActiveMMLPartIndex();
		// グローバルテンポリストに設定する
		mmlManager.getActiveTrack().getMMLEventList().get(index).setGlobalTempoList(originalList.getGlobalTempoList());
		mmlManager.updateActivePart(true);
		if (pianoRollView != null) {
			pianoRollView.setSequenceTick(initialPosition);
		}
	}

	public void showDialog() {
		dialog.getContentPane().add(panel);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(200, 200));
		dialog.setLocationRelativeTo(parentFrame);
		dialog.setVisible(true);
	}

	@Override
	public void insertUpdate(DocumentEvent e) {
		SwingUtilities.invokeLater(() -> applyStyle());
	}

	@Override
	public void removeUpdate(DocumentEvent e) {
		SwingUtilities.invokeLater(() -> applyStyle());
	}

	@Override
	public void changedUpdate(DocumentEvent e) {}

	@Override
	public void caretUpdate(CaretEvent e) {
		int dot = e.getDot();
		try {
			// 休符も含めたTick長を求める
			String text = textPane.getText(0, dot);
			text = MMLFile.toMMLText(text).replaceAll("R", "c").replaceAll("r", "c");
			var eventList = new MMLEventList(text);
			if (pianoRollView != null) {
				pianoRollView.setSequenceTick(eventList.getTickLength());
				mmlManager.updatePianoRollView();
			}
			if (parentFrame != null) {
				parentFrame.repaint();
			}
		} catch (BadLocationException e1) {}
	}
}

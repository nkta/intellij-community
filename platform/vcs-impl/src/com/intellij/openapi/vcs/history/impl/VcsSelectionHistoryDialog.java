// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.history.impl;

import com.intellij.diff.Block;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.LoadingDiffRequest;
import com.intellij.diff.requests.MessageDiffRequest;
import com.intellij.diff.requests.NoDiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.diff.util.IntPair;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.BackgroundTaskUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.FrameWrapper;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.ui.popup.util.PopupUtil;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.annotate.ShowAllAffectedGenericAction;
import com.intellij.openapi.vcs.changes.issueLinks.IssueLinkHtmlRenderer;
import com.intellij.openapi.vcs.changes.issueLinks.TableLinkMouseListener;
import com.intellij.openapi.vcs.history.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.Wrapper;
import com.intellij.ui.table.TableView;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.AnimatedIcon;
import com.intellij.util.ui.*;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.*;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.intellij.util.ObjectUtils.notNull;

public final class VcsSelectionHistoryDialog extends FrameWrapper implements DataProvider {
  private static final VcsRevisionNumber LOCAL_REVISION_NUMBER = new VcsRevisionNumber() {
    @NotNull
    @Override
    public String asString() {
      return "Local Changes";
    }

    @Override
    public int compareTo(@NotNull VcsRevisionNumber vcsRevisionNumber) {
      return 0;
    }

    @Override
    public String toString() {
      return asString();
    }
  };

  private static final float DIFF_SPLITTER_PROPORTION = 0.5f;
  private static final float COMMENTS_SPLITTER_PROPORTION = 0.8f;
  private static final String DIFF_SPLITTER_PROPORTION_KEY = "file.history.selection.diff.splitter.proportion";
  private static final String COMMENTS_SPLITTER_PROPORTION_KEY = "file.history.selection.comments.splitter.proportion";

  private static final Block EMPTY_BLOCK = new Block("", 0, 0);

  @NotNull private final Project myProject;
  @NotNull private final VirtualFile myFile;
  @NotNull private final AbstractVcs myActiveVcs;
  @NotNull private final VcsHistoryProvider myVcsHistoryProvider;

  private final ColumnInfo[] myDefaultColumns;
  private ListTableModel<VcsFileRevision> myListModel;
  private final TableView<VcsFileRevision> myList;

  private final Splitter mySplitter;
  private final DiffRequestPanel myDiffPanel;
  private final JCheckBox myChangesOnlyCheckBox = new JCheckBox(VcsBundle.message("checkbox.show.changed.revisions.only"));
  private final JLabel myStatusLabel = new JBLabel();
  private final AnimatedIcon myStatusSpinner = new AsyncProcessIcon("VcsSelectionHistoryDialog");
  private final JEditorPane myComments;
  private final Wrapper myDetailsPanel = new Wrapper();

  @Nullable private Consumer<VcsFileRevision> mySelectedRevisionListener;

  @NotNull private final MergingUpdateQueue myUpdateQueue;
  @NotNull private final BlockLoader myBlockLoader;

  private boolean myRevisionsLoaded = false;
  private boolean myIsDuringUpdate = false;
  private boolean myIsDisposed = false;

  public VcsSelectionHistoryDialog(@NotNull Project project,
                                   @NotNull VirtualFile file,
                                   @NotNull Document document,
                                   @NotNull VcsHistoryProvider vcsHistoryProvider,
                                   @NotNull AbstractVcs vcs,
                                   int selectionStart,
                                   int selectionEnd,
                                   @NotNull String title) {
    super(project, "VCS.FileHistoryDialog");

    myProject = project;
    myFile = file;
    myActiveVcs = vcs;
    myVcsHistoryProvider = vcsHistoryProvider;

    myComments = new JEditorPane(UIUtil.HTML_MIME, "");
    myComments.setPreferredSize(new JBDimension(150, 100));
    myComments.setEditable(false);
    myComments.addHyperlinkListener(BrowserHyperlinkListener.INSTANCE);

    myDefaultColumns = new ColumnInfo[]{
      new FileHistoryPanelImpl.RevisionColumnInfo(null),
      new FileHistoryPanelImpl.DateColumnInfo(),
      new FileHistoryPanelImpl.AuthorColumnInfo(),
      new FileHistoryPanelImpl.MessageColumnInfo(project)};

    myListModel = new ListTableModel<>(myDefaultColumns);
    myListModel.setSortable(false);
    myList = new TableView<>(myListModel);
    new TableLinkMouseListener().installOn(myList);

    myList.getEmptyText().setText(VcsBundle.message("history.loading.revisions"));

    myDiffPanel = DiffManager.getInstance().createRequestPanel(myProject, this, getFrame());
    myUpdateQueue = new MergingUpdateQueue("VcsSelectionHistoryDialog", 300, true, myList, this);

    mySplitter = new JBSplitter(true, DIFF_SPLITTER_PROPORTION_KEY, DIFF_SPLITTER_PROPORTION);

    mySplitter.setFirstComponent(myDiffPanel.getComponent());
    mySplitter.setSecondComponent(createBottomPanel());

    final ListSelectionListener selectionListener = new ListSelectionListener() {
      @Override
      public void valueChanged(ListSelectionEvent e) {
        final VcsFileRevision revision;
        if (myList.getSelectedRowCount() == 1 && !myList.isEmpty()) {
          revision = myList.getItems().get(myList.getSelectedRow());
          String message = IssueLinkHtmlRenderer.formatTextIntoHtml(myProject, revision.getCommitMessage());
          myComments.setText(message);
          myComments.setCaretPosition(0);
        }
        else {
          revision = null;
          myComments.setText("");
        }
        if (mySelectedRevisionListener != null) {
          mySelectedRevisionListener.consume(revision);
        }
        updateDiff();
      }
    };
    myList.getSelectionModel().addListSelectionListener(selectionListener);

    final VcsConfiguration configuration = VcsConfiguration.getInstance(myProject);
    myChangesOnlyCheckBox.setSelected(configuration.SHOW_ONLY_CHANGED_IN_SELECTION_DIFF);
    myChangesOnlyCheckBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        configuration.SHOW_ONLY_CHANGED_IN_SELECTION_DIFF = myChangesOnlyCheckBox.isSelected();
        updateRevisionsList();
      }
    });

    final DefaultActionGroup popupActions = new DefaultActionGroup();
    popupActions.add(new MyDiffAction());
    popupActions.add(new MyDiffAfterWithLocalAction());
    popupActions.add(ShowAllAffectedGenericAction.getInstance());
    popupActions.add(ActionManager.getInstance().getAction(VcsActions.ACTION_COPY_REVISION_NUMBER));
    PopupHandler.installPopupHandler(myList, popupActions, ActionPlaces.UPDATE_POPUP, ActionManager.getInstance());

    for (AnAction action : popupActions.getChildren(null)) {
      action.registerCustomShortcutSet(action.getShortcutSet(), mySplitter);
    }

    setTitle(title);
    setComponent(mySplitter);
    setPreferredFocusedComponent(myList);
    closeOnEsc();

    myBlockLoader = new BlockLoader(myActiveVcs, myFile, document, selectionStart, selectionEnd) {
      @Override
      protected void notifyError(@NotNull VcsException e) {
        runOnEdt(() -> {
          PopupUtil.showBalloonForComponent(mySplitter, canNoLoadMessage(e), MessageType.ERROR, true, myProject);
        });
      }

      @Override
      protected void notifyUpdate(boolean shouldFlush) {
        myUpdateQueue.queue(new Update(this) {
          @Override
          public void run() {
            updateStatusPanel();
            updateRevisionsList();
          }
        });
        if (shouldFlush) {
          runOnEdt(() -> myUpdateQueue.flush());
        }
      }

      private void runOnEdt(@NotNull Runnable task) {
        GuiUtils.invokeLaterIfNeeded(() -> {
          VcsSelectionHistoryDialog dialog = VcsSelectionHistoryDialog.this;
          if (!dialog.isDisposed() && dialog.getFrame().isShowing()) {
            task.run();
          }
        }, ModalityState.stateForComponent(mySplitter));
      }
    };
    myBlockLoader.start(this);

    updateRevisionsList();
    updateDiff();
  }

  @NotNull
  private static String canNoLoadMessage(@Nullable VcsException e) {
    return "Can not load revision contents" + (e != null ? ": " + e.getMessage() : "");
  }

  @CalledInAwt
  private void updateRevisionsList() {
    if (myIsDuringUpdate) return;
    try {
      myIsDuringUpdate = true;

      BlockData data = myBlockLoader.getLoadedData();
      if (data.getRevisions().isEmpty()) return;

      if (!myRevisionsLoaded) {
        myRevisionsLoaded = true;

        JRootPane rootPane = ((RootPaneContainer)getFrame()).getRootPane();
        final VcsDependentHistoryComponents components = myVcsHistoryProvider.getUICustomization(data.getSession(), rootPane);

        ColumnInfo[] additionalColumns = components.getColumns();
        myListModel = new ListTableModel<>(ArrayUtil.mergeArrays(myDefaultColumns, additionalColumns, ColumnInfo[]::new));
        myListModel.setSortable(false);
        myList.setModel(myListModel);

        mySelectedRevisionListener = components.getRevisionListener();
        myDetailsPanel.setContent(components.getDetailsComponent());
      }

      List<VcsFileRevision> newItems;
      if (myChangesOnlyCheckBox.isSelected()) {
        newItems = filteredRevisions(data);
      }
      else {
        newItems = data.getRevisions();
      }

      IntPair range = getSelectedRevisionsRange(data);
      List<VcsFileRevision> oldSelection = data.getRevisions().subList(range.val1, range.val2);

      myListModel.setItems(newItems);

      myList.setSelection(oldSelection);
      if (myList.getSelectedRowCount() == 0) {
        int index = getNearestVisibleRevision(ContainerUtil.getFirstItem(oldSelection), data);
        if (myList.getRowCount() != 0) myList.getSelectionModel().setSelectionInterval(index, index);
      }
      TableUtil.scrollSelectionToVisible(myList);
    }
    finally {
      myIsDuringUpdate = false;
    }

    updateDiff();
  }

  private void updateStatusPanel() {
    BlockData data = myBlockLoader.getLoadedData();

    if (data.isLoading()) {
      VcsFileRevision revision = data.getCurrentLoadingRevision();
      String message = revision != null
                       ? String.format("Loading revision <tt>%s</tt>...", VcsUtil.getShortRevisionString(revision.getRevisionNumber()))
                       : "Loading...";
      int totalRevisions = data.getRevisions().size();
      if (totalRevisions != 0) {
        message += String.format(" (%s/%s)", data.myBlocks.size(), totalRevisions);
      }
      myStatusLabel.setText(String.format("<html>%s</html>", message));

      myStatusSpinner.resume();
      myStatusSpinner.setVisible(true);
    }
    else {
      myStatusLabel.setText("");
      myStatusSpinner.suspend();
      myStatusSpinner.setVisible(false);
    }
  }

  @NotNull
  private IntPair getSelectedRevisionsRange(@NotNull BlockData blockData) {
    List<VcsFileRevision> selection = myList.getSelectedObjects();
    if (selection.isEmpty()) return new IntPair(0, 0);
    int startIndex = blockData.getRevisions().indexOf(ContainerUtil.getFirstItem(selection));
    int endIndex = blockData.getRevisions().indexOf(ContainerUtil.getLastItem(selection));
    return new IntPair(startIndex, endIndex + 1);
  }

  private int getNearestVisibleRevision(@Nullable VcsFileRevision anchor, @NotNull BlockData blockData) {
    int anchorIndex = blockData.getRevisions().indexOf(anchor);
    if (anchorIndex == -1) return 0;

    for (int i = anchorIndex - 1; i > 0; i--) {
      int index = myListModel.indexOf(blockData.getRevisions().get(i));
      if (index != -1) return index;
    }
    return 0;
  }

  private List<VcsFileRevision> filteredRevisions(@NotNull BlockData blockData) {
    ArrayList<VcsFileRevision> result = new ArrayList<>();
    BlockData data = myBlockLoader.getLoadedData();

    for (int i = 1; i < blockData.getRevisions().size(); i++) {
      Block block1 = data.getBlock(i - 1);
      Block block2 = data.getBlock(i);
      if (block1 == null || block2 == null) break;
      if (!block1.getLines().equals(block2.getLines())) {
        result.add(blockData.getRevisions().get(i - 1));
      }
      if (block2 == EMPTY_BLOCK) break;
    }

    int initialCommit = blockData.getRevisions().size() - 1;
    Block initialCommitBlock = data.getBlock(initialCommit);
    if (initialCommitBlock != null && initialCommitBlock != EMPTY_BLOCK) {
      result.add(blockData.getRevisions().get(initialCommit));
    }

    return result;
  }

  private void updateDiff() {
    if (myIsDisposed || myIsDuringUpdate) return;

    if (myList.getSelectedRowCount() == 0) {
      myDiffPanel.setRequest(NoDiffRequest.INSTANCE);
      return;
    }

    BlockData blockData = myBlockLoader.getLoadedData();

    int count = blockData.getRevisions().size();
    if (count == 0) {
      myDiffPanel.setRequest(new LoadingDiffRequest());
      return;
    }

    IntPair range = getSelectedRevisionsRange(blockData);
    int revIndex1 = range.val2;
    int revIndex2 = range.val1;

    if (revIndex1 == count && revIndex2 == count) {
      myDiffPanel.setRequest(NoDiffRequest.INSTANCE);
      return;
    }

    DiffContent content1 = createDiffContent(revIndex1, blockData);
    DiffContent content2 = createDiffContent(revIndex2, blockData);
    String title1 = createDiffContentTitle(revIndex1, blockData);
    String title2 = createDiffContentTitle(revIndex2, blockData);
    if (content1 != null && content2 != null) {
      myDiffPanel.setRequest(new SimpleDiffRequest(null, content1, content2, title1, title2), new IntPair(revIndex1, revIndex2));
      return;
    }

    if (blockData.isLoading()) {
      myDiffPanel.setRequest(new LoadingDiffRequest());
    }
    else {
      myDiffPanel.setRequest(new MessageDiffRequest(canNoLoadMessage(blockData.getException())));
    }
  }

  @Nullable
  private static String createDiffContentTitle(int index, @NotNull BlockData data) {
    if (index >= data.getRevisions().size()) return null;
    return VcsBundle.message("diff.content.title.revision.number", data.getRevisions().get(index).getRevisionNumber());
  }

  @Nullable
  private DiffContent createDiffContent(int index, @NotNull BlockData data) {
    if (index >= data.getRevisions().size()) return DiffContentFactory.getInstance().createEmpty();
    Block block = data.getBlock(index);
    if (block == null) return null;
    if (block == EMPTY_BLOCK) return DiffContentFactory.getInstance().createEmpty();
    DocumentContent documentContent = DiffContentFactory.getInstance().create(block.getBlockContent(), myFile.getFileType());
    documentContent.putUserData(DiffUserDataKeysEx.LINE_NUMBER_CONVERTOR, value -> value + block.getStart());
    return documentContent;
  }

  @Override
  public void dispose() {
    myIsDisposed = true;
    super.dispose();
  }

  private JComponent createBottomPanel() {
    JBSplitter splitter = new JBSplitter(true, COMMENTS_SPLITTER_PROPORTION_KEY, COMMENTS_SPLITTER_PROPORTION);
    splitter.setDividerWidth(4);

    JPanel tablePanel = new JPanel(new BorderLayout());
    tablePanel.add(ScrollPaneFactory.createScrollPane(myList), BorderLayout.CENTER);

    JPanel statusPanel = new JPanel(new FlowLayout());
    statusPanel.add(myStatusSpinner);
    statusPanel.add(myStatusLabel);

    JPanel separatorPanel = new JPanel(new BorderLayout());
    separatorPanel.add(myChangesOnlyCheckBox, BorderLayout.WEST);
    separatorPanel.add(statusPanel, BorderLayout.EAST);

    tablePanel.add(separatorPanel, BorderLayout.NORTH);

    splitter.setFirstComponent(tablePanel);
    splitter.setSecondComponent(createComments());

    return splitter;
  }

  private JComponent createComments() {
    JPanel panel = new JPanel(new BorderLayout(4, 4));
    panel.add(new JLabel("Commit Message:"), BorderLayout.NORTH);
    panel.add(ScrollPaneFactory.createScrollPane(myComments), BorderLayout.CENTER);

    final Splitter splitter = new Splitter(false);
    splitter.setFirstComponent(panel);
    splitter.setSecondComponent(myDetailsPanel);
    return splitter;
  }

  @Override
  public Object getData(@NotNull @NonNls String dataId) {
    if (CommonDataKeys.PROJECT.is(dataId)) {
      return myProject;
    }
    else if (VcsDataKeys.VCS_VIRTUAL_FILE.is(dataId)) {
      return myFile;
    }
    else if (VcsDataKeys.VCS_FILE_REVISION.is(dataId)) {
      VcsFileRevision selectedObject = myList.getSelectedObject();
      return selectedObject instanceof CurrentRevision ? null : selectedObject;
    }
    else if (VcsDataKeys.VCS_FILE_REVISIONS.is(dataId)) {
      return ContainerUtil.filter(myList.getSelectedObjects(), Conditions.notEqualTo(myBlockLoader.getLocalRevision()))
        .toArray(new VcsFileRevision[0]);
    }
    else if (VcsDataKeys.VCS.is(dataId)) {
      return myActiveVcs.getKeyInstanceMethod();
    }
    else if (PlatformDataKeys.HELP_ID.is(dataId)) {
      return notNull(myVcsHistoryProvider.getHelpId(), "reference.dialogs.vcs.selection.history");
    }
    return null;
  }

  private class MyDiffAction extends DumbAwareAction {
    MyDiffAction() {
      super(VcsBundle.message("action.name.compare"), VcsBundle.message("action.description.compare"), AllIcons.Actions.Diff);
      setShortcutSet(CommonShortcuts.getDiff());
    }

    @Override
    public void update(@NotNull final AnActionEvent e) {
      e.getPresentation().setEnabled(myList.getSelectedRowCount() > 1 ||
                                     myList.getSelectedRowCount() == 1 && myList.getSelectedObject() != myBlockLoader.getLocalRevision());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      BlockData blockData = myBlockLoader.getLoadedData();
      if (blockData.getRevisions().isEmpty()) return;

      IntPair range = getSelectedRevisionsRange(blockData);

      List<VcsFileRevision> revisions = blockData.getRevisions();
      VcsFileRevision beforeRevision = range.val2 < revisions.size() ? revisions.get(range.val2) : VcsFileRevision.NULL;
      VcsFileRevision afterRevision = revisions.get(range.val1);

      FilePath filePath = VcsUtil.getFilePath(myFile);

      if (range.val2 - range.val1 > 1) {
        getDiffHandler().showDiffForTwo(myProject, filePath, beforeRevision, afterRevision);
      }
      else {
        getDiffHandler().showDiffForOne(e, myProject, filePath, beforeRevision, afterRevision);
      }
    }
  }

  private class MyDiffAfterWithLocalAction extends DumbAwareAction {
    MyDiffAfterWithLocalAction() {
      ActionUtil.copyFrom(this, "Vcs.ShowDiffWithLocal");
    }

    @Override
    public void update(@NotNull final AnActionEvent e) {
      e.getPresentation().setEnabled(myList.getSelectedRowCount() == 1 && myList.getSelectedObject() != myBlockLoader.getLocalRevision());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      VcsFileRevision revision = myList.getSelectedObject();
      if (revision == null) return;

      FilePath filePath = VcsUtil.getFilePath(myFile);

      getDiffHandler().showDiffForTwo(myProject, filePath, revision, myBlockLoader.getLocalRevision());
    }
  }

  @NotNull
  private DiffFromHistoryHandler getDiffHandler() {
    VcsHistoryProvider historyProvider = myActiveVcs.getVcsHistoryProvider();
    DiffFromHistoryHandler handler = historyProvider != null ? historyProvider.getHistoryDiffHandler() : null;
    return handler != null ? handler : new StandardDiffFromHistoryHandler();
  }

  private static abstract class BlockLoader {
    @NotNull private final Object LOCK = new Object();

    private final AbstractVcs myVcs;
    private final VirtualFile myFile;
    private final VcsFileRevision myLocalRevision;

    private VcsHistorySession mySession;
    private final List<VcsFileRevision> myRevisions = new ArrayList<>();
    private final List<Block> myBlocks = new ArrayList<>();

    @Nullable private VcsException myException;
    private boolean myIsLoading = true;
    private VcsFileRevision myCurrentLoadingRevision;

    BlockLoader(@NotNull AbstractVcs vcs,
                @NotNull VirtualFile file,
                @NotNull Document document,
                int selectionStart,
                int selectionEnd) {
      myVcs = vcs;
      myFile = file;
      myLocalRevision = new CurrentRevision(file, LOCAL_REVISION_NUMBER);

      String[] lastContent = Block.tokenize(document.getText());
      myBlocks.add(new Block(lastContent, selectionStart, selectionEnd + 1));
    }

    public VcsFileRevision getLocalRevision() {
      return myLocalRevision;
    }

    @NotNull
    public BlockData getLoadedData() {
      synchronized (LOCK) {
        return new BlockData(myIsLoading, mySession, new ArrayList<>(myRevisions), new ArrayList<>(myBlocks),
                             myException, myCurrentLoadingRevision);
      }
    }

    public void start(@NotNull Disposable disposable) {
      notifyUpdate(true);

      BackgroundTaskUtil.executeOnPooledThread(disposable, () -> {
        try {
          VcsHistorySession session = VcsCachingHistory.collectSession(myVcs, VcsUtil.getFilePath(myFile), null);

          synchronized (LOCK) {
            mySession = session;
            myRevisions.add(myLocalRevision);
            myRevisions.addAll(session.getRevisionList());
          }
          notifyUpdate(true);

          // first block is loaded in constructor
          for (int index = 1; index < myRevisions.size(); index++) {
            ProgressManager.checkCanceled();

            Block block = myBlocks.get(index - 1);
            VcsFileRevision revision = myRevisions.get(index);

            synchronized (LOCK) {
              myCurrentLoadingRevision = revision;
            }
            notifyUpdate(false);

            Block previousBlock = createBlock(block, revision);

            synchronized (LOCK) {
              myBlocks.add(previousBlock);
            }
            notifyUpdate(false);
          }
        }
        catch (VcsException e) {
          synchronized (LOCK) {
            myException = e;
          }
          notifyError(e);
        }
        finally {
          synchronized (LOCK) {
            myIsLoading = false;
            myCurrentLoadingRevision = null;
          }
          notifyUpdate(true);
        }
      });
    }

    @CalledInBackground
    protected abstract void notifyError(@NotNull VcsException e);

    @CalledInBackground
    protected abstract void notifyUpdate(boolean shouldFlush);

    @NotNull
    private Block createBlock(@NotNull Block block, @NotNull VcsFileRevision revision) throws VcsException {
      if (block == EMPTY_BLOCK) return EMPTY_BLOCK;

      String revisionContent = loadContents(revision);

      Block newBlock = block.createPreviousBlock(revisionContent);
      return newBlock.getStart() != newBlock.getEnd() ? newBlock : EMPTY_BLOCK;
    }

    @NotNull
    private String loadContents(@NotNull VcsFileRevision revision) throws VcsException {
      try {
        byte[] bytes = revision.loadContent();
        if (bytes == null) throw new VcsException("Failed to load content for revision " + revision.getRevisionNumber().asString());
        return new String(bytes, myFile.getCharset());
      }
      catch (IOException e) {
        throw new VcsException(e);
      }
    }
  }

  private static class BlockData {
    private final boolean myIsLoading;
    private final VcsHistorySession mySession;
    @NotNull private final List<VcsFileRevision> myRevisions;
    @NotNull private final List<? extends Block> myBlocks;
    @Nullable private final VcsException myException;
    @Nullable private final VcsFileRevision myCurrentLoadingRevision;

    BlockData(boolean isLoading,
              @Nullable VcsHistorySession session,
              @NotNull List<VcsFileRevision> revisions,
              @NotNull List<? extends Block> blocks,
              @Nullable VcsException exception,
              @Nullable VcsFileRevision currentLoadingRevision) {
      myIsLoading = isLoading;
      mySession = session;
      myRevisions = revisions;
      myBlocks = blocks;
      myException = exception;
      myCurrentLoadingRevision = currentLoadingRevision;
    }

    public boolean isLoading() {
      return myIsLoading;
    }

    @Nullable
    public VcsException getException() {
      return myException;
    }

    @Nullable
    public VcsFileRevision getCurrentLoadingRevision() {
      return myCurrentLoadingRevision;
    }

    @Nullable
    public Block getBlock(int index) {
      if (myBlocks.size() <= index) return null;
      return myBlocks.get(index);
    }

    @Nullable
    private VcsHistorySession getSession() {
      return mySession;
    }

    @NotNull
    private List<VcsFileRevision> getRevisions() {
      return myRevisions;
    }
  }
}

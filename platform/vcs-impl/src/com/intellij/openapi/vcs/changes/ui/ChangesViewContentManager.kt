// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager.Companion.LOCAL_CHANGES
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager.Companion.SHELF
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.IJSwingUtilities
import com.intellij.util.ObjectUtils.tryCast
import java.util.function.Predicate

internal val isCommitToolWindow = Registry.get("vcs.commit.tool.window")
internal val COMMIT_TOOL_WINDOW_CONTENT_FILTER: (String) -> Boolean = { it == LOCAL_CHANGES || it == SHELF }

class ChangesViewContentManager : ChangesViewContentI, Disposable {

  private val toolWindows = mutableSetOf<ToolWindow>()
  private val addedContents = mutableListOf<Content>()

  private val contentManagers: Collection<ContentManager> get() = toolWindows.map { it.contentManager }

  private fun getToolWindowIdFor(contentName: String): String =
    if (isCommitToolWindow.asBoolean() && COMMIT_TOOL_WINDOW_CONTENT_FILTER(contentName)) COMMIT_TOOLWINDOW_ID
    else TOOLWINDOW_ID

  private fun Content.resolveToolWindowId(): String = getToolWindowIdFor(tabName)

  private fun Content.resolveToolWindow(): ToolWindow? {
    val toolWindowId = resolveToolWindowId()
    return toolWindows.find { it.id == toolWindowId }
  }

  private fun Content.resolveContentManager(): ContentManager? = resolveToolWindow()?.contentManager

  override fun attachToolWindow(toolWindow: ToolWindow) {
    toolWindows.add(toolWindow)
    initContentManager(toolWindow.contentManager)
  }

  private fun initContentManager(contentManager: ContentManager) {
    val listener = ContentProvidersListener()
    contentManager.addContentManagerListener(listener)
    Disposer.register(this, Disposable { contentManager.removeContentManagerListener(listener) })

    val contents = addedContents.filter { it.resolveContentManager() === contentManager }
    contents.forEach {
      addIntoCorrectPlace(contentManager, it)
      IJSwingUtilities.updateComponentTreeUI(it.component)
    }
    addedContents.removeAll(contents)

    // Ensure that first tab is selected after tabs reordering
    val firstContent = contentManager.getContent(0)
    if (firstContent != null) contentManager.setSelectedContent(firstContent)
  }

  override fun dispose() {
    for (content in addedContents) {
      Disposer.dispose(content)
    }
    addedContents.clear()
  }

  override fun addContent(content: Content) {
    val contentManager = content.resolveContentManager()
    if (contentManager == null) {
      addedContents.add(content)
    }
    else {
      addIntoCorrectPlace(contentManager, content)
    }
  }

  override fun removeContent(content: Content) {
    val contentManager = content.manager
    if (contentManager == null || contentManager.isDisposed) {
      addedContents.remove(content)
      Disposer.dispose(content)
    }
    else {
      contentManager.removeContent(content, true)
    }
  }

  override fun setSelectedContent(content: Content) {
    setSelectedContent(content, false)
  }

  override fun setSelectedContent(content: Content, requestFocus: Boolean) {
    content.manager?.setSelectedContent(content, requestFocus)
  }

  override fun <T : Any> getActiveComponent(aClass: Class<T>): T? =
    contentManagers.mapNotNull { tryCast(it.selectedContent?.component, aClass) }.firstOrNull()

  fun isContentSelected(contentName: String): Boolean =
    contentManagers.any { it.selectedContent?.tabName == contentName }

  override fun selectContent(tabName: String) {
    selectContent(tabName, false)
  }

  fun selectContent(tabName: String, requestFocus: Boolean) {
    val content = contentManagers.flatMap { it.contents.asList() }.find { it.tabName == tabName } ?: return
    content.manager?.setSelectedContent(content, requestFocus)
  }

  override fun findContents(predicate: Predicate<Content>): List<Content> {
    val allContents = contentManagers.flatMap { it.contents.asList() } + addedContents
    return allContents.filter { predicate.test(it) }.toList()
  }

  private inner class ContentProvidersListener : ContentManagerListener {
    override fun selectionChanged(event: ContentManagerEvent) {
      val content = event.content
      val provider = content.getUserData(CONTENT_PROVIDER_SUPPLIER_KEY)?.invoke() ?: return
      provider.initTabContent(content)
      IJSwingUtilities.updateComponentTreeUI(content.component)
      content.putUserData(CONTENT_PROVIDER_SUPPLIER_KEY, null)
    }
  }

  enum class TabOrderWeight(val tabName: String?, val weight: Int) {
    LOCAL_CHANGES(ChangesViewContentManager.LOCAL_CHANGES, 10),
    REPOSITORY(ChangesViewContentManager.REPOSITORY, 20),
    INCOMING(ChangesViewContentManager.INCOMING, 30),
    SHELF(ChangesViewContentManager.SHELF, 40),
    BRANCHES(ChangesViewContentManager.BRANCHES, 50),
    OTHER(null, 100),
    LAST(null, Integer.MAX_VALUE)
  }

  private fun addIntoCorrectPlace(contentManager: ContentManager, content: Content) {
    val weight = getContentWeight(content)

    val contents = contentManager.contents

    var index = -1
    for (i in contents.indices) {
      val oldWeight = getContentWeight(contents[i])
      if (oldWeight > weight) {
        index = i
        break
      }
    }

    if (index == -1) index = contents.size
    contentManager.addContent(content, index)
  }

  companion object {
    @JvmField
    val TOOLWINDOW_ID: String = ToolWindowId.VCS
    internal const val COMMIT_TOOLWINDOW_ID: String = "Commit"

    @JvmField
    val CONTENT_PROVIDER_SUPPLIER_KEY = Key.create<() -> ChangesViewContentProvider>("CONTENT_PROVIDER_SUPPLIER")

    @JvmStatic
    fun getInstance(project: Project): ChangesViewContentI {
      return project.getService(ChangesViewContentI::class.java)
    }

    @JvmStatic
    fun getToolWindowIdFor(project: Project, contentName: String): String? =
      (getInstance(project) as? ChangesViewContentManager)?.getToolWindowIdFor(contentName)

    @JvmStatic
    fun getToolWindowFor(project: Project, contentName: String): ToolWindow? =
      ToolWindowManager.getInstance(project).getToolWindow(getToolWindowIdFor(project, contentName))

    @JvmField
    val ORDER_WEIGHT_KEY = Key.create<Int>("ChangesView.ContentOrderWeight")

    const val LOCAL_CHANGES = "Local Changes"
    const val REPOSITORY = "Repository"
    const val INCOMING = "Incoming"
    const val SHELF = "Shelf"
    const val BRANCHES = "Branches"

    private fun getContentWeight(content: Content): Int {
      val userData = content.getUserData(ORDER_WEIGHT_KEY)
      if (userData != null) return userData

      val tabName = content.tabName
      for (value in TabOrderWeight.values()) {
        if (value.tabName != null && value.tabName == tabName) {
          return value.weight
        }
      }

      return TabOrderWeight.OTHER.weight
    }
  }
}

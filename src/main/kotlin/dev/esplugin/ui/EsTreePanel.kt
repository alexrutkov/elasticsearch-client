package dev.esplugin.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import dev.esplugin.client.EsClient
import dev.esplugin.client.EsException
import dev.esplugin.client.EsResponse
import dev.esplugin.console.ConsoleStore
import dev.esplugin.console.EsConsoleDialog
import dev.esplugin.console.SavedRequest
import dev.esplugin.settings.ConnectionConfig
import dev.esplugin.settings.ConnectionDialog
import dev.esplugin.settings.ConnectionManager
import dev.esplugin.settings.SecretStore
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class EsTreePanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val manager = ConnectionManager.getInstance()
    private val treeModel = DefaultTreeModel(RootNode())
    private val tree = Tree(treeModel)
    private val toolbar: ActionToolbar

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = 0
        tree.cellRenderer = EsTreeRenderer()

        val group = DefaultActionGroup().apply {
            add(AddConnectionAction(this@EsTreePanel))
            add(EditConnectionAction(this@EsTreePanel))
            add(RemoveConnectionAction(this@EsTreePanel))
            addSeparator()
            add(NewConsoleAction(this@EsTreePanel))
            add(RefreshConnectionsAction(this@EsTreePanel))
        }
        toolbar = ActionManager.getInstance().createActionToolbar("ElasticsearchClient", group, true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree))

        tree.addTreeSelectionListener { toolbar.updateActionsAsync() }
        installPopupMenu()

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                when (val node = event.path.lastPathComponent) {
                    is ConfigNode -> loadHealth(node)
                    is CategoryNode -> if (!node.loaded && !node.loading) loadCategory(node)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })

        reloadConnections()
    }

    fun reloadConnections() {
        val root = RootNode()
        manager.connections.forEach { config ->
            val configNode = ConfigNode(config)
            Category.entries.forEach { category ->
                configNode.add(CategoryNode(config, category))
            }
            root.add(configNode)
        }
        treeModel.setRoot(root)

        if (root.childCount == 1) {
            tree.expandPath(TreePath(root).pathByAddingChild(root.getChildAt(0)))
        }
    }

    fun hasSelectedConnection(): Boolean = selectedConfigNode() != null

    fun addConnection() {
        val dialog = ConnectionDialog(project, ConnectionConfig(), isNew = true)
        if (!dialog.showAndGet()) return

        val config = dialog.resultConfig
        manager.add(config)
        if (dialog.resultSecret.isNotEmpty()) {
            SecretStore.set(config.id, dialog.resultSecret)
        }
        reloadConnections()
    }

    fun editSelectedConnection() {
        val configNode = selectedConfigNode() ?: return
        val dialog = ConnectionDialog(project, configNode.config.copy(), isNew = false)
        if (!dialog.showAndGet()) return

        manager.update(dialog.resultConfig)
        if (dialog.resultSecret.isNotEmpty()) {
            SecretStore.set(dialog.resultConfig.id, dialog.resultSecret)
        }
        reloadConnections()
    }

    fun removeSelectedConnection() {
        val configNode = selectedConfigNode() ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "Remove connection \"${configNode.config.name}\"?",
            "Remove Connection",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return

        manager.remove(configNode.config)
        SecretStore.set(configNode.config.id, null)
        ConsoleStore.getInstance().removeConnection(configNode.config.id)
        reloadConnections()
    }

    fun newConsole() {
        val configNode = selectedConfigNode()
        if (configNode == null) {
            EsNotifications.info(project, "Elasticsearch", "Select a connection first")
            return
        }
        openConsole(configNode.config)
    }

    fun refreshCategory(connectionId: String, category: Category? = null) {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        for (i in 0 until root.childCount) {
            val configNode = root.getChildAt(i) as? ConfigNode ?: continue
            if (configNode.config.id != connectionId) continue
            for (j in 0 until configNode.childCount) {
                val categoryNode = configNode.getChildAt(j) as? CategoryNode ?: continue
                if (category != null && categoryNode.category != category) continue
                if (categoryNode.category.isLocal) {
                    populateLocalCategory(categoryNode)
                } else {
                    categoryNode.loaded = false
                    val path = TreePath(configNode.path).pathByAddingChild(categoryNode)
                    if (tree.isExpanded(path)) {
                        loadCategory(categoryNode)
                    }
                }
            }
        }
    }

    private fun openConsole(
        config: ConnectionConfig,
        initial: SavedRequest? = null,
        editingConsoleId: String? = null,
    ) {
        EsConsoleDialog(
            project,
            config,
            SecretStore.get(config.id),
            initial,
            editingConsoleId,
        ) { category -> refreshCategory(config.id, category) }.show()
    }

    private fun openItem(node: ItemNode) {
        val configNode = findConfigNode(node) ?: return
        val config = configNode.config
        val store = ConsoleStore.getInstance()
        when (node.kind) {
            ItemKind.CONSOLE -> {
                val saved = store.findConsole(node.payloadId ?: return) ?: return
                openConsole(config, saved.copy(), saved.id)
            }

            ItemKind.HISTORY -> {
                val saved = store.findHistory(node.payloadId ?: return) ?: return
                openConsole(config, saved.copy())
            }

            ItemKind.QUERY -> {
                val query = store.findQuery(node.payloadId ?: return) ?: return
                openConsole(
                    config,
                    SavedRequest(
                        connectionId = config.id,
                        name = query.name,
                        method = "POST",
                        path = query.path,
                        body = query.body,
                    ),
                )
            }

            ItemKind.PLAIN -> Unit
        }
    }

    private fun deleteItem(node: ItemNode) {
        val configNode = findConfigNode(node) ?: return
        val id = node.payloadId ?: return
        val category = when (node.kind) {
            ItemKind.CONSOLE -> Category.CONSOLES
            ItemKind.HISTORY -> Category.REQUESTS
            ItemKind.QUERY -> Category.QUERIES
            ItemKind.PLAIN -> return
        }
        val answer = Messages.showYesNoDialog(
            project,
            "Delete \"${node.title}\"?",
            "Delete",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return

        val store = ConsoleStore.getInstance()
        when (node.kind) {
            ItemKind.CONSOLE -> store.deleteConsole(id)
            ItemKind.HISTORY -> store.deleteHistory(id)
            ItemKind.QUERY -> store.deleteQuery(id)
            ItemKind.PLAIN -> Unit
        }
        refreshCategory(configNode.config.id, category)
    }

    private fun findConfigNode(node: DefaultMutableTreeNode): ConfigNode? {
        var current: DefaultMutableTreeNode? = node
        while (current != null) {
            if (current is ConfigNode) return current
            current = current.parent as? DefaultMutableTreeNode
        }
        return null
    }

    private fun installPopupMenu() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    val node = tree.getPathForLocation(event.x, event.y)?.lastPathComponent as? ItemNode
                        ?: return
                    openItem(node)
                }
            }

            override fun mousePressed(event: MouseEvent) = maybeShowPopup(event)

            override fun mouseReleased(event: MouseEvent) = maybeShowPopup(event)

            private fun maybeShowPopup(event: MouseEvent) {
                if (!event.isPopupTrigger) return
                val path = tree.getPathForLocation(event.x, event.y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return

                val menu = JPopupMenu()
                when (node) {
                    is ItemNode -> {
                        val parentCategory = (node.parent as? CategoryNode)?.category
                        if (node.kind == ItemKind.PLAIN) {
                            if (parentCategory == Category.INDICES || parentCategory == Category.ALIASES) {
                                menu.add(menuItem("Open in Console") {
                                    findConfigNode(node)?.let { configNode ->
                                        openConsole(
                                            configNode.config,
                                            SavedRequest(
                                                connectionId = configNode.config.id,
                                                name = node.title,
                                                method = "GET",
                                                path = "/${node.title}/_search",
                                            ),
                                        )
                                    }
                                })
                            }
                        } else {
                            menu.add(menuItem("Open") { openItem(node) })
                            menu.addSeparator()
                            menu.add(menuItem("Delete") { deleteItem(node) })
                        }
                    }

                    is CategoryNode -> {
                        when (node.category) {
                            Category.CONSOLES, Category.QUERIES ->
                                menu.add(menuItem("New Console") { openConsole(node.config) })

                            Category.REQUESTS -> {
                                menu.add(menuItem("New Console") { openConsole(node.config) })
                                menu.add(menuItem("Clear History") {
                                    ConsoleStore.getInstance().clearHistory(node.config.id)
                                    refreshCategory(node.config.id, Category.REQUESTS)
                                })
                            }

                            else -> Unit
                        }
                        menu.addSeparator()
                        menu.add(menuItem("Refresh") {
                            if (node.category.isLocal) {
                                populateLocalCategory(node)
                            } else {
                                node.loaded = false
                                loadCategory(node)
                            }
                        })
                    }

                    is ConfigNode -> menu.add(menuItem("New Console") { openConsole(node.config) })
                }
                if (menu.componentCount > 0) {
                    menu.show(tree, event.x, event.y)
                }
            }
        })
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem =
        JMenuItem(text).apply { addActionListener { action() } }

    private fun selectedConfigNode(): ConfigNode? {
        var current = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        while (current !is ConfigNode) {
            current = current.parent as? DefaultMutableTreeNode ?: return null
        }
        return current
    }

    private fun loadHealth(node: ConfigNode) {
        if (node.healthLoading) return
        node.healthLoading = true
        val config = node.config
        val secret = SecretStore.get(config.id)

        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { EsClient(config, secret).checkConnection() }
            invokeLater {
                node.healthLoading = false
                result.onSuccess { info ->
                    node.health = info.health?.status ?: "ok"
                }.onFailure {
                    node.health = "error"
                }
                treeModel.nodeChanged(node)
            }
        }
    }

    private fun loadCategory(node: CategoryNode) {
        if (node.category.isLocal) {
            // Do not mutate the model from TreeWillExpandListener directly.
            invokeLater { populateLocalCategory(node) }
            return
        }

        node.loading = true
        node.removeAllChildren()
        node.add(InfoNode("Loading..."))
        treeModel.nodeStructureChanged(node)

        val config = node.config
        val secret = SecretStore.get(config.id)
        val category = node.category

        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching { fetchCategory(config, secret, category) }
            invokeLater {
                node.removeAllChildren()
                node.loading = false
                node.loaded = true

                result.onSuccess { items ->
                    if (items.isEmpty()) {
                        node.add(InfoNode("Nothing found"))
                    } else {
                        items.forEach { node.add(it) }
                    }
                }.onFailure { error ->
                    node.add(InfoNode(error.message ?: error.javaClass.simpleName, error = true))
                }
                treeModel.nodeStructureChanged(node)
            }
        }
    }

    private fun invokeLater(action: () -> Unit) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) action()
        }, ModalityState.any())
    }

    private fun populateLocalCategory(node: CategoryNode) {
        val store = ConsoleStore.getInstance()
        val items: List<DefaultMutableTreeNode> = when (node.category) {
            Category.CONSOLES -> store.consoles(node.config.id).map { saved ->
                ItemNode(
                    title = saved.name,
                    details = "${saved.method} ${saved.path}",
                    kind = ItemKind.CONSOLE,
                    payloadId = saved.id,
                )
            }

            Category.REQUESTS -> store.history(node.config.id).map { saved ->
                ItemNode(
                    title = "${saved.method} ${saved.path}",
                    details = formatTime(saved.timestamp),
                    kind = ItemKind.HISTORY,
                    payloadId = saved.id,
                )
            }

            Category.QUERIES -> store.queries(node.config.id).map { query ->
                val preview = query.body.lineSequence().firstOrNull()?.trim()?.take(60).orEmpty()
                ItemNode(
                    title = query.name,
                    details = preview.ifBlank { query.path },
                    kind = ItemKind.QUERY,
                    payloadId = query.id,
                )
            }

            else -> emptyList()
        }

        node.removeAllChildren()
        node.loading = false
        node.loaded = true
        if (items.isEmpty()) {
            node.add(InfoNode("Nothing found"))
        } else {
            items.forEach { node.add(it) }
        }
        treeModel.nodeStructureChanged(node)
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun fetchCategory(
        config: ConnectionConfig,
        secret: String?,
        category: Category,
    ): List<DefaultMutableTreeNode> {
        val client = EsClient(config, secret)
        return when (category) {
            Category.CONSOLES, Category.REQUESTS, Category.QUERIES -> emptyList()
            Category.INDICES -> loadIndices(client)
            Category.ALIASES -> loadAliases(client)
            Category.DATA_STREAMS -> loadDataStreams(client)
            Category.NODES -> loadNodes(client)
        }
    }

    private fun loadIndices(client: EsClient): List<DefaultMutableTreeNode> {
        val response = client.get(
            "/_cat/indices",
            mapOf(
                "format" to "json",
                "h" to "index,health,status,docs.count,store.size",
                "s" to "index",
            ),
        )
        ensureSuccess(response)
        return jsonArray(response.body).mapNotNull { obj ->
            val name = obj.string("index") ?: return@mapNotNull null
            val details = listOfNotNull(
                obj.string("health"),
                obj.string("status"),
                obj.string("docs.count")?.let { "$it docs" },
                obj.string("store.size"),
            ).joinToString(" · ")
            ItemNode(name, details)
        }
    }

    private fun loadAliases(client: EsClient): List<DefaultMutableTreeNode> {
        val response = client.get(
            "/_cat/aliases",
            mapOf("format" to "json", "h" to "alias,index"),
        )
        ensureSuccess(response)
        return jsonArray(response.body).mapNotNull { obj ->
            val alias = obj.string("alias") ?: return@mapNotNull null
            ItemNode(alias, "→ ${obj.string("index").orEmpty()}")
        }
    }

    private fun loadDataStreams(client: EsClient): List<DefaultMutableTreeNode> {
        val response = client.get("/_data_stream")
        if (response.status == 400 || response.status == 404) {
            return emptyList()
        }
        ensureSuccess(response)
        val root = runCatching { JsonParser.parseString(response.body).asJsonObject }.getOrNull()
            ?: return emptyList()
        return jsonArray(root.get("data_streams")).mapNotNull { obj ->
            val name = obj.string("name") ?: return@mapNotNull null
            val status = obj.string("status")
            val indices = jsonArray(obj.get("indices")).size
            ItemNode(name, listOfNotNull(status, "$indices indices").joinToString(" · "))
        }
    }

    private fun loadNodes(client: EsClient): List<DefaultMutableTreeNode> {
        val response = client.get(
            "/_cat/nodes",
            mapOf(
                "format" to "json",
                "h" to "name,ip,node.role,master,heap.percent,cpu",
            ),
        )
        ensureSuccess(response)
        return jsonArray(response.body).mapNotNull { obj ->
            val name = obj.string("name") ?: return@mapNotNull null
            val details = listOfNotNull(
                obj.string("ip"),
                obj.string("node.role"),
                obj.string("master")?.let { "master: $it" },
                obj.string("heap.percent")?.let { "heap $it%" },
                obj.string("cpu")?.let { "cpu $it%" },
            ).joinToString(" · ")
            ItemNode(name, details)
        }
    }

    private fun ensureSuccess(response: EsResponse) {
        if (!response.isSuccess) {
            throw EsException(
                "HTTP ${response.status}: ${response.body.take(300).ifBlank { "empty response" }}",
                response.status,
            )
        }
    }

    private fun jsonArray(body: String): List<JsonObject> {
        val element = runCatching { JsonParser.parseString(body) }.getOrNull()
        return jsonArray(element)
    }

    private fun jsonArray(element: JsonElement?): List<JsonObject> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString
}

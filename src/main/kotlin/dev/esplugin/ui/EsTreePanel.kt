package dev.esplugin.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import dev.esplugin.client.EsClient
import dev.esplugin.client.EsException
import dev.esplugin.client.EsResponse
import dev.esplugin.settings.ConnectionConfig
import dev.esplugin.settings.ConnectionDialog
import dev.esplugin.settings.ConnectionManager
import dev.esplugin.settings.SecretStore
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
            add(RefreshConnectionsAction(this@EsTreePanel))
        }
        toolbar = ActionManager.getInstance().createActionToolbar("ElasticsearchClient", group, true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree))

        tree.addTreeSelectionListener { toolbar.updateActionsAsync() }

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
        manager.connections.forEach { root.add(ConfigNode(it)) }
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
        reloadConnections()
    }

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
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) action()
        }
    }

    private fun fetchCategory(
        config: ConnectionConfig,
        secret: String?,
        category: Category,
    ): List<DefaultMutableTreeNode> {
        val client = EsClient(config, secret)
        return when (category) {
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

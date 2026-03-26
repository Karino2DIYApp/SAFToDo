package io.github.karino2.saftodo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.BufferedWriter
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {
    companion object {
        const val LAST_URI_KEY = "last_uri_path"
        const val LAST_TAB_KEY = "last_tab_name"

        fun sharedPreferences(ctx: Context) =
            ctx.getSharedPreferences("SAF_TO_DO", Context.MODE_PRIVATE)!!

        fun lastUri(ctx: Context) = sharedPreferences(ctx).getString(LAST_URI_KEY, null)?.toUri()

        fun writeLastUri(ctx: Context, uri: Uri) = sharedPreferences(ctx)
            .edit(commit = true) {
                putString(LAST_URI_KEY, uri.toString())
            }

        fun lastTabName(ctx: Context) = sharedPreferences(ctx).getString(LAST_TAB_KEY, null)

        fun writeLastTabName(ctx: Context, name: String?) = sharedPreferences(ctx)
            .edit(commit = true) {
                putString(LAST_TAB_KEY, name)
            }

        fun showMessage(ctx: Context, msg : String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private var _url : Uri? = null

    private val rootDir: DocumentFile
        get() = _url?.let { DocumentFile.fromTreeUri(this, it) } ?: throw Exception("No url set")

    private fun writeLastUri(uri: Uri) = writeLastUri(this, uri)
    private val lastUri: Uri?
        get() = lastUri(this)

    private val getRootDirUrl = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            writeLastUri(it)
            openRootDir(it)
        }
    }

    private var mdFiles: List<DocumentFile> = emptyList()
    private lateinit var viewPager: ViewPager2

    private fun openRootDir(url: Uri) {
        _url = url
        setupTabs()
    }

    private fun setupTabs() {
        mdFiles = try {
            rootDir.listFiles()
                .filter { it.isFile && it.name?.endsWith(".md") == true }
                .sortedBy { it.name }
        } catch (e: Exception) {
            emptyList()
        }

        if (mdFiles.isEmpty()) {
            showMessage(this, "No .md files found in the selected directory")
        }

        viewPager = findViewById(R.id.viewPager)
        viewPager.isUserInputEnabled = false
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = FilePageAdapter(this, mdFiles)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = mdFiles[position].name?.removeSuffix(".md")
        }.attach()

        // Restore last selected tab
        val lastTab = lastTabName(this)
        if (lastTab != null) {
            val index = mdFiles.indexOfFirst { it.name?.removeSuffix(".md") == lastTab }
            if (index >= 0) {
                viewPager.setCurrentItem(index, false)
            }
        }

        // Save selected tab whenever it changes
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position < mdFiles.size) {
                    val tabName = mdFiles[position].name?.removeSuffix(".md")
                    writeLastTabName(this@MainActivity, tabName)
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            showAddItemDialog()
        }

        try {
            lastUri?.let {
                openRootDir(it)
                return
            }
        } catch(_: Exception) {
            showMessage(this, "Can't open saved dir. Please reopen.")
        }
        getRootDirUrl.launch(null)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu?.findItem(R.id.action_add_item)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_file -> {
                showAddFileDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAddFileDialog() {
        val editText = EditText(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle("New File Name")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val name = editText.text.toString()
                if (name.isNotEmpty()) {
                    val fileName = if (name.endsWith(".md")) name else "$name.md"
                    rootDir.createFile("text/markdown", fileName)
                    setupTabs()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        editText.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun showAddItemDialog() {
        if (mdFiles.isEmpty()) return
        
        val currentFile = mdFiles[viewPager.currentItem]
        val editText = EditText(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle("New Item")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val itemText = editText.text.toString()
                if (itemText.isNotEmpty()) {
                    appendToFile(currentFile.uri, "- $itemText")
                    viewPager.adapter?.notifyItemChanged(viewPager.currentItem)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        editText.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun appendToFile(uri: Uri, text: String) {
        try {
            contentResolver.openOutputStream(uri, "wa")?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).apply {
                    write(text)
                    newLine()
                    flush()
                }
            }
        } catch (e: Exception) {
            showMessage(this, "Error writing: ${e.message}")
        }
    }

    fun readAllLines(uri: Uri): List<String> {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readLines()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun writeAllLines(uri: Uri, lines: List<String>) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).apply {
                    lines.forEach {
                        write(it)
                        newLine()
                    }
                    flush()
                }
            }
        } catch (e: Exception) {
            showMessage(this, "Error saving: ${e.message}")
        }
    }

    class FilePageAdapter(private val activity: MainActivity, private val files: List<DocumentFile>) :
        RecyclerView.Adapter<FilePageAdapter.PageViewHolder>() {

        class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val recyclerView: RecyclerView = view.findViewById(R.id.recyclerView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.todo_list_view, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val file = files[position]
            holder.recyclerView.layoutManager = LinearLayoutManager(activity)
            holder.recyclerView.addItemDecoration(DividerItemDecoration(activity, DividerItemDecoration.VERTICAL))
            
            refreshList(holder.recyclerView, file.uri)

            val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val itemPos = viewHolder.bindingAdapterPosition
                    deleteToDoItem(file.uri, itemPos)
                    refreshList(holder.recyclerView, file.uri)
                }
                
                override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                    return 0.3f
                }
            }
            ItemTouchHelper(swipeHandler).attachToRecyclerView(holder.recyclerView)
        }

        private fun refreshList(recyclerView: RecyclerView, uri: Uri) {
            val items = loadToDoItems(uri)
            recyclerView.adapter = ToDoLinesAdapter(items) { itemPos ->
                showEditItemDialog(uri, itemPos) {
                    refreshList(recyclerView, uri)
                }
            }
        }

        private fun deleteToDoItem(uri: Uri, position: Int) {
            val allLines = activity.readAllLines(uri)
            val todoIndices = allLines.indices.filter { allLines[it].trimStart().startsWith("- ") }
            if (position < todoIndices.size) {
                val originalIndex = todoIndices[position]
                val newLines = allLines.filterIndexed { index, _ -> index != originalIndex }
                activity.writeAllLines(uri, newLines)
            }
        }

        private fun showEditItemDialog(uri: Uri, position: Int, onUpdated: () -> Unit) {
            val allLines = activity.readAllLines(uri)
            val todoIndices = allLines.indices.filter { allLines[it].trimStart().startsWith("- ") }
            if (position >= todoIndices.size) return
            
            val originalIndex = todoIndices[position]
            val currentText = allLines[originalIndex].trimStart().removePrefix("-").trim()
            
            val editText = EditText(activity)
            editText.setText(currentText)
            
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Edit Item")
                .setView(editText)
                .setPositiveButton("OK") { _, _ ->
                    val newText = editText.text.toString()
                    if (newText.isNotEmpty()) {
                        val leadingSpaces = allLines[originalIndex].takeWhile { it.isWhitespace() }
                        val updatedLines = allLines.toMutableList()
                        updatedLines[originalIndex] = "$leadingSpaces- $newText"
                        activity.writeAllLines(uri, updatedLines)
                        onUpdated()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()
            
            editText.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.show()
        }

        override fun getItemCount() = files.size

        private fun loadToDoItems(uri: Uri): List<String> {
            return activity.readAllLines(uri)
                .filter { it.trimStart().startsWith("- ") }
                .map { it.trimStart().removePrefix("-").trim() }
        }
    }

    class ToDoLinesAdapter(private val lines: List<String>, private val onItemClick: (Int) -> Unit) :
        RecyclerView.Adapter<ToDoLinesAdapter.LineViewHolder>() {

        class LineViewHolder(view: View, onClick: (Int) -> Unit) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
            init {
                view.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onClick(pos)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return LineViewHolder(view, onItemClick)
        }

        override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
            holder.textView.text = lines[position]
        }

        override fun getItemCount() = lines.size
    }
}

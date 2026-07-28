package com.varun.upitracker.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varun.upitracker.R
import com.varun.upitracker.database.entity.Category

class CategorySettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: CategorySettingsViewModel
    private lateinit var adapter: CategorySettingsAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_settings)

        viewModel = ViewModelProvider(
            this,
            AppViewModelFactory(applicationContext)
        )[CategorySettingsViewModel::class.java]

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tvEmpty = findViewById(R.id.tvEmptyCategories)
        adapter = CategorySettingsAdapter(
            onRename = ::showRenameDialog,
            onDelete = ::showDeleteDialog
        )

        findViewById<TextView>(R.id.btnBackCategories).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAddCategoryToolbar).setOnClickListener { showCreateDialog() }
        findViewById<RecyclerView>(R.id.rvCategories).apply {
            layoutManager = LinearLayoutManager(this@CategorySettingsActivity)
            adapter = this@CategorySettingsActivity.adapter
        }
        viewModel.categories.observe(this) { categories ->
            adapter.submit(categories)
            tvEmpty.visibility = if (categories.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
    }

    private fun loadCategories() {
        viewModel.loadCategories()
    }

    private fun showCreateDialog() {
        showNameDialog(
            title = "Add category",
            initialValue = ""
        ) { value ->
            viewModel.createCategory(value, ::showMutationError)
        }
    }

    private fun showRenameDialog(category: Category) {
        showNameDialog(
            title = "Rename category",
            initialValue = category.name
        ) { value ->
            viewModel.renameCategory(category.id, value, ::showMutationError)
        }
    }

    private fun showDeleteDialog(category: Category) {
        viewModel.isCategoryInUse(category.id) { isUsed ->
            if (!isUsed) {
                AlertDialog.Builder(this@CategorySettingsActivity)
                    .setTitle("Delete category?")
                    .setMessage("Delete '${category.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewModel.deleteCategory(category.id, null, ::showMutationError)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@isCategoryInUse
            }

            viewModel.replacementCategories(category.id) { replacements ->
            if (replacements.isEmpty()) {
                Toast.makeText(
                    this@CategorySettingsActivity,
                    "Create another category first so this one can be reassigned.",
                    Toast.LENGTH_SHORT
                ).show()
                return@replacementCategories
            }

            var selectedIndex = 0
            val labels = replacements.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@CategorySettingsActivity)
                .setTitle("Choose replacement")
                .setSingleChoiceItems(labels, 0) { _, which -> selectedIndex = which }
                .setMessage("This category is already in use. Reassign it before deleting.")
                .setPositiveButton("Reassign & delete") { _, _ ->
                    viewModel.deleteCategory(category.id, replacements[selectedIndex].id, ::showMutationError)
                }
                .setNegativeButton("Cancel", null)
                .show()
            }
        }
    }

    private fun showNameDialog(title: String, initialValue: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initialValue)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMutationError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private class CategorySettingsAdapter(
    private val onRename: (Category) -> Unit,
    private val onDelete: (Category) -> Unit
) : RecyclerView.Adapter<CategorySettingsAdapter.VH>() {

    private val items = mutableListOf<Category>()

    fun submit(categories: List<Category>) {
        items.clear()
        items.addAll(categories)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_setting, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val category = items[position]
        holder.tvName.text = category.name
        holder.btnRename.setOnClickListener { onRename(category) }
        holder.btnDelete.setOnClickListener { onDelete(category) }
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvCategoryName)
        val btnRename: TextView = view.findViewById(R.id.btnRenameCategory)
        val btnDelete: TextView = view.findViewById(R.id.btnDeleteCategory)
    }
}

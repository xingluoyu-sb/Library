package com.library.app.ui.pickers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 系统默认的这几个契约只申请读权限，拿到的 uri 之后没法删源文件。
 * 这三个版本额外要一个写权限，删除、重定位之后才能真正动到磁盘上的文件。
 */
private const val READ_WRITE =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

class OpenTreeWithWrite : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).apply { addFlags(READ_WRITE) }
}

class OpenDocumentsWithWrite : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply { addFlags(READ_WRITE) }
}

class OpenDocumentWithWrite : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply { addFlags(READ_WRITE) }
}

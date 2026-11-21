package com.library.app.data

import android.content.Context
import com.library.app.data.crypto.DataKey
import com.library.app.data.crypto.SecretCrypto
import com.library.app.data.db.AppDatabase
import com.library.app.data.files.BackupManager
import com.library.app.data.files.FileStore
import com.library.app.data.files.FolderImporter
import com.library.app.data.files.ImportRunner
import com.library.app.data.files.TrashStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase by lazy { AppDatabase(appContext) }
    val fileStore: FileStore by lazy { FileStore(appContext) }
    val trashStore: TrashStore by lazy { TrashStore(appContext, fileStore) }
    val repository: Repository by lazy { Repository(database, fileStore, trashStore) }
    val dataKey: DataKey by lazy { DataKey(appContext, database) }
    val secretCrypto: SecretCrypto by lazy { SecretCrypto(appContext, database, dataKey) }
    val folderImporter: FolderImporter by lazy { FolderImporter(appContext, repository) }
    val importRunner: ImportRunner by lazy { ImportRunner(folderImporter) }
    val backupManager: BackupManager by lazy {
        BackupManager(appContext, database, dataKey, fileStore)
    }
    val branding: BrandingStore by lazy { BrandingStore(appContext) }
    val security: SecurityPrefs by lazy { SecurityPrefs(appContext) }
    val sortPrefs: SortPrefs by lazy { SortPrefs(appContext) }
    val appLock: AppLock by lazy { AppLock(appContext) }
}

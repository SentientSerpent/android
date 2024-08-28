/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Alper Ozturk <alper.ozturk@nextcloud.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.nextcloud.utils.extensions

import com.nextcloud.client.database.dao.OfflineOperationDao
import com.nextcloud.client.database.entity.OfflineOperationEntity
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile

private const val DELIMITER = '/'

fun OfflineOperationDao.getAllSubdirectories(
    fileId: Long,
    fileDataStorageManager: FileDataStorageManager
): List<OfflineOperationEntity> {
    val result = mutableListOf<OfflineOperationEntity>()
    val queue = ArrayDeque<Long>()
    queue.add(fileId)
    val processedIds = mutableSetOf<Long>()

    while (queue.isNotEmpty()) {
        val currentFileId = queue.removeFirst()
        if (currentFileId in processedIds || currentFileId == 1L) continue

        processedIds.add(currentFileId)

        val subDirectories = getSubDirectoriesByParentOCFileId(currentFileId)
        result.addAll(subDirectories)

        subDirectories.forEach {
            val ocFile = fileDataStorageManager.getFileByDecryptedRemotePath(it.path)
            ocFile?.fileId?.let { newFileId ->
                if (newFileId != 1L && newFileId !in processedIds) {
                    queue.add(newFileId)
                }
            }
        }
    }

    return result
}

fun OfflineOperationDao.updatePathAndSubPaths(
    oldPath: String,
    newPath: String,
    oldFileName: String,
    newFileName: String
) {
    val operationsToUpdate = getSubDirs(oldPath, oldFileName)

    operationsToUpdate.forEach { operation ->
        val newOperationFileName = operation.filename?.replaceFirst(oldFileName, newFileName)
        val newOperationPath = operation.path?.replaceFirst(oldPath, newPath)
        operation.path = newOperationPath
        operation.filename = newOperationFileName
        update(operation)
    }
}

fun OfflineOperationDao.deleteOperation(file: OCFile, fileDataStorageManager: FileDataStorageManager) {
    getAllSubdirectories(file.fileId, fileDataStorageManager).forEach {
        delete(it)
    }

    file.decryptedRemotePath?.let {
        val entity = getByPath(it)
        entity?.let {
            delete(entity)
        }
    }

    fileDataStorageManager.removeFile(file, true, true)
}

fun OfflineOperationDao.updateNextOperations(
    fileDataStorageManager: FileDataStorageManager
) {
    getAll()
        .mapNotNull { nextOperation ->
            nextOperation.parentOCFileId?.let { parentId ->
                fileDataStorageManager.getFileById(parentId)?.let { ocFile ->
                    ocFile.decryptedRemotePath?.let { updatedPath ->
                        val newParentPath = ocFile.parentRemotePath
                        val newPath = updatedPath + nextOperation.filename + DELIMITER

                        if (newParentPath != nextOperation.parentPath || newPath != nextOperation.path) {
                            nextOperation.apply {
                                parentPath = newParentPath
                                path = newPath
                            }
                        } else null
                    }
                }
            }
        }
        .forEach { update(it) }
}

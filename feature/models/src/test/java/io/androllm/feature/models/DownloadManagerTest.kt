package io.androllm.feature.models

import android.content.Context
import androidx.work.WorkManager
import io.androllm.core.database.repository.ModelRepository
import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import io.androllm.feature.models.downloader.DownloadManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val modelRepository: ModelRepository = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)

    private lateinit var downloadManager: DownloadManager

    @Before
    fun setUp() {
        mockkStatic(WorkManager::class)
        coEvery { WorkManager.getInstance(context) } returns workManager
        coEvery { context.getExternalFilesDir(any()) } returns null
        coEvery { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))

        downloadManager = DownloadManager(context, modelRepository)
    }

    @Test
    fun `pauseDownload cancels work and updates status to PAUSED`() = runTest {
        downloadManager.pauseDownload("model-1")

        coVerify { workManager.cancelUniqueWork("download_model-1") }
        coVerify { modelRepository.updateDownloadState("model-1", false, DownloadStatus.PAUSED, null) }
    }

    @Test
    fun `cancelDownload cancels work and deletes model`() = runTest {
        downloadManager.cancelDownload("model-1")

        coVerify { workManager.cancelUniqueWork("download_model-1") }
        coVerify { modelRepository.deleteById("model-1") }
    }

    @Test
    fun `startDownload with invalid url marks ERROR and never enqueues`() = runTest {
        val model = Model(
            id = "model-bad-url",
            name = "Broken Model",
            downloadUrl = "file:///sdcard/model.litertlm"
        )

        downloadManager.startDownload(model)

        coVerify {
            modelRepository.updateDownloadState("model-bad-url", false, DownloadStatus.ERROR, null)
        }
        coVerify(exactly = 0) {
            workManager.enqueueUniqueWork(
                any<String>(),
                any<androidx.work.ExistingWorkPolicy>(),
                any<androidx.work.OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `startDownload with valid url enqueues unique work`() = runTest {
        val model = Model(
            id = "model-ok",
            name = "Good Model",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
            fileSize = 497664000L
        )

        downloadManager.startDownload(model)

        coVerify(timeout = 3000) {
            workManager.enqueueUniqueWork(
                "download_model-ok",
                any<androidx.work.ExistingWorkPolicy>(),
                any<androidx.work.OneTimeWorkRequest>()
            )
        }
        coVerify(exactly = 0) {
            modelRepository.updateDownloadState("model-ok", false, DownloadStatus.ERROR, null)
        }
    }
}

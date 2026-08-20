package com.faker1024.icloudsync.core.database

import androidx.room.TypeConverter
import com.faker1024.icloudsync.domain.model.ImportBatchState
import com.faker1024.icloudsync.domain.model.ImportedMediaState
import com.faker1024.icloudsync.domain.model.MediaKind

class DatabaseConverters {
    @TypeConverter
    fun batchStateToString(value: ImportBatchState): String = value.name

    @TypeConverter
    fun stringToBatchState(value: String): ImportBatchState = ImportBatchState.valueOf(value)

    @TypeConverter
    fun mediaStateToString(value: ImportedMediaState): String = value.name

    @TypeConverter
    fun stringToMediaState(value: String): ImportedMediaState = ImportedMediaState.valueOf(value)

    @TypeConverter
    fun mediaKindToString(value: MediaKind): String = value.name

    @TypeConverter
    fun stringToMediaKind(value: String): MediaKind = MediaKind.valueOf(value)
}

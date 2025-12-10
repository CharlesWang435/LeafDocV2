package com.leafdoc.app.data.local

import androidx.room.TypeConverter
import com.leafdoc.app.data.model.DiagnosisStatus

class Converters {
    @TypeConverter
    fun fromDiagnosisStatus(status: DiagnosisStatus): String {
        return status.name
    }

    @TypeConverter
    fun toDiagnosisStatus(value: String): DiagnosisStatus {
        return DiagnosisStatus.valueOf(value)
    }
}

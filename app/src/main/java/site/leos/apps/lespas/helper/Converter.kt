package site.leos.apps.lespas.helper

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class Converter {
    @TypeConverter
    fun fromLong(value: Long): LocalDateTime {
        return if (value > 9999999999) Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDateTime() else Instant.ofEpochSecond(value).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }

    @TypeConverter
    fun toLong(date: LocalDateTime): Long {
        return date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
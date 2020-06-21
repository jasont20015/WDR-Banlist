package com.wdrbanlist;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Data
@AllArgsConstructor
public class Ban {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    // "Lynx Titan"
    @SerializedName("CURRENT RSN")
    private String rsn;

    // "20-Jun-2020" GMT
    @SerializedName("DATE OF BAN")
    private String date;

    // "Scammer (Twisted Bow)"
    @SerializedName("Category")
    private String category;


}

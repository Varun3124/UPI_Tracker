package com.varun.upitracker.domain.model

data class NetWorthSummary(
    val liquidAssetsPaise: Long,
    val uninvestedPaise: Long,
    val investedPaise: Long,
    val fixedDepositPaise: Long,
    val withMePaise: Long,
    val unsettledIouPaise: Long,
    val totalPaise: Long
)

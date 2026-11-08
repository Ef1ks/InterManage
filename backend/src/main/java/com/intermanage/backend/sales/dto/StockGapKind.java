package com.intermanage.backend.sales.dto;

/**
 * Co się dzieje z artykułem, który dobrze się sprzedaje. {@code MISSING}: niski stan i cisza w sprzedaży, towar
 * przyjeżdża regularnie. {@code ONE_OFF}: to samo przy towarze z jednej, dwóch dostaw spoza cadencier, zwykle akcja,
 * która po prostu się skończyła. {@code RUNNING_OUT}: jeszcze się sprzedaje, ale zapas wystarczy na kilka dni.
 * {@code WRONG_STOCK}: sprzedaje się mimo stanu 0 albo ujemnego, więc towar jest, a stan się nie zgadza.
 */
public enum StockGapKind {
    MISSING,
    ONE_OFF,
    RUNNING_OUT,
    WRONG_STOCK
}

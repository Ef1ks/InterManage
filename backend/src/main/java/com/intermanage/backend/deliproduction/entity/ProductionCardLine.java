package com.intermanage.backend.deliproduction.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Pozycja karty: kopia kodu, nazwy i parametrów schematu z chwili dodania, ilość i godzina rozpoczęcia procesu.
 * Zakończenie i przekazanie do schłodzenia są godzinę później, ważność liczy się z daty produkcji.
 */
@Entity
@Table(name = "production_card_lines")
public class ProductionCardLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false, updatable = false)
    private ProductionCard card;

    @Column(nullable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private ProductionArticle article;

    @Column(length = 20)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "start_hour", nullable = false)
    private int startHour;

    @Embedded
    private ProcessParameters parameters;

    protected ProductionCardLine() {
    }

    public ProductionCardLine(ProductionArticle article, String code, String name, ProcessParameters parameters,
            BigDecimal quantity, int startHour) {
        this.article = article;
        this.code = code;
        this.name = name;
        this.parameters = parameters;
        this.quantity = quantity;
        this.startHour = startHour;
    }

    void attachTo(ProductionCard card, int position) {
        this.card = card;
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public ProductionArticle getArticle() {
        return article;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public int getStartHour() {
        return startHour;
    }

    public int getEndHour() {
        return startHour + 1;
    }

    public ProcessParameters getParameters() {
        return parameters != null ? parameters : ProcessParameters.EMPTY;
    }

    /** Data produkcji + ważność w dniach; bez ważności {@code null}. */
    public LocalDate expiryDate(LocalDate productionDate) {
        Integer days = getParameters().shelfLifeDays();
        return days != null ? productionDate.plusDays(days) : null;
    }
}

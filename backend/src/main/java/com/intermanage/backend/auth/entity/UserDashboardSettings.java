package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Ustawienia pulpitu konta, wspólne dla aplikacji w przeglądarce i mobilnej. */
@Entity
@Table(name = "user_dashboard_settings")
public class UserDashboardSettings {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "sales_chart_metric", nullable = false, length = 20)
    private DashboardChartMetric salesChartMetric = DashboardChartMetric.SALES;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserDashboardSettings() {
    }

    public UserDashboardSettings(AppUser user) {
        this.user = user;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public DashboardChartMetric getSalesChartMetric() {
        return salesChartMetric;
    }

    public void setSalesChartMetric(DashboardChartMetric salesChartMetric) {
        this.salesChartMetric = salesChartMetric;
    }
}

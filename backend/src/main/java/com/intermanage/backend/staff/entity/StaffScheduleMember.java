package com.intermanage.backend.staff.entity;

import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Miejsce pracownika w grafiku działu: jego grupa i kolejność. Sam wygląd tabeli, więc wpis się nadpisuje. */
@Entity
@Table(name = "staff_schedule_members")
public class StaffScheduleMember {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false, updatable = false)
    private StaffDepartment department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private StaffScheduleGroup group;

    @Column(nullable = false)
    private int position;

    protected StaffScheduleMember() {
    }

    public StaffScheduleMember(Store store, StaffDepartment department, StaffEmployee employee) {
        this.store = store;
        this.department = department;
        this.employee = employee;
    }

    public void place(StaffScheduleGroup group, int position) {
        this.group = group;
        this.position = position;
    }

    public StaffEmployee getEmployee() {
        return employee;
    }

    public StaffScheduleGroup getGroup() {
        return group;
    }

    public int getPosition() {
        return position;
    }
}

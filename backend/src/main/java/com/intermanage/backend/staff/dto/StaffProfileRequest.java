package com.intermanage.backend.staff.dto;

import java.time.LocalDate;

import com.intermanage.backend.staff.entity.EducationLevel;

public record StaffProfileRequest(LocalDate birthDate, EducationLevel educationLevel, LocalDate educationEndDate,
        boolean firstJob, LocalDate medicalExamUntil, LocalDate bhpTrainingUntil, LocalDate sanitaryUntil,
        Boolean pit2, Boolean higherCosts, Boolean youthReliefWaived, Boolean student, Boolean voluntarySickness) {
}

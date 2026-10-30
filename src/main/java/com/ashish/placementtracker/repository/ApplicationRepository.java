package com.ashish.placementtracker.repository;

import com.ashish.placementtracker.model.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ApplicationRepository {
    PlacementApplication create(String company, String role, LocalDate appliedOn,
            LocalDate deadline) throws IOException;
    List<PlacementApplication> findAll() throws IOException;
    Optional<PlacementApplication> findById(int id) throws IOException;
    void save(PlacementApplication application) throws IOException;
    void deleteById(int id) throws IOException;
}

package dev.kaiwen.bikes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "availability")
@Getter
@Setter
@NoArgsConstructor
public class Availability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "number", nullable = false)
    private Integer number;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "number", insertable = false, updatable = false)
    private Station station;

    @Column(name = "available_bikes", nullable = false)
    private Integer availableBikes;

    @Column(name = "available_bike_stands", nullable = false)
    private Integer availableBikeStands;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "last_update", nullable = false)
    private Long lastUpdate;

    /** Naive UTC wall time (scraper utc_now_naive / JCDecaux from_unix_ms_utc). */
    @Column(nullable = false)
    private LocalDateTime timestamp;

    /** Naive UTC scrape time. */
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;
}

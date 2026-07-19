package com.kindred.api.profile

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "interests")
class Interest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true, length = 64)
    var slug: String,

    @Column(nullable = false, length = 100)
    var label: String,
)

interface InterestRepository : JpaRepository<Interest, Long> {
    fun findBySlugIn(slugs: Collection<String>): List<Interest>
    fun findAllByOrderByLabelAsc(): List<Interest>
}

package dev.fleet.api.db;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tenant")
public class TenantEntity {
    @TableId private Long id;
    private String name;
    private Integer ingestRatePerSec;
    private Integer llmRatePerMin;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getIngestRatePerSec() {
        return ingestRatePerSec;
    }

    public void setIngestRatePerSec(Integer rate) {
        this.ingestRatePerSec = rate;
    }

    public Integer getLlmRatePerMin() {
        return llmRatePerMin;
    }

    public void setLlmRatePerMin(Integer rate) {
        this.llmRatePerMin = rate;
    }
}

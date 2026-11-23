package com.acme.marketing.control.infrastructure.persistence.mapper;

import com.acme.marketing.control.application.BundleScheduleStore;
import org.apache.ibatis.annotations.Mapper;

/** SQL 及时间顺序均由专用 XML 维护。 */
@Mapper
public interface BundleScheduleMapper extends BundleScheduleStore { }

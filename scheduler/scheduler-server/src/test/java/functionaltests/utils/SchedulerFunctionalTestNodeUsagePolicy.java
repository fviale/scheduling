/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package functionaltests.utils;

import java.io.File;

import org.junit.BeforeClass;


/**
 * Tests which start the scheduler and configure it using node usage policy
 *
 * Every concrete subclass should be added to one of functionaltests.StandardTestSuite or functionaltests.RegressionTestSuite
 * @see functionaltests.StandardTestSuite
 * @see functionaltests.RegressionTestSuite
 */
public class SchedulerFunctionalTestNodeUsagePolicy extends SchedulerFunctionalTestWithCustomConfigAndRestart {

    @BeforeClass
    public static void startDedicatedScheduler() throws Exception {
        schedulerHelper.log("Start Scheduler in non-fork mode.");
        schedulerHelper = new SchedulerTHelper(false,
                                               new File(SchedulerFunctionalTestNodeUsagePolicy.class.getResource("/functionaltests/config/functionalTSchedulerProperties-nodeusagepolicy.ini")
                                                                                                    .toURI()).getAbsolutePath());

        schedulerHelper.addExtraNodes(20);
    }

}

package org.mifosplatform.integrationtests;

import static org.junit.Assert.assertEquals;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mifosplatform.integrationtests.common.ClientHelper;
import org.mifosplatform.integrationtests.common.CommonConstants;
import org.mifosplatform.integrationtests.common.GlobalConfigurationHelper;
import org.mifosplatform.integrationtests.common.HolidayHelper;
import org.mifosplatform.integrationtests.common.SchedulerJobHelper;
import org.mifosplatform.integrationtests.common.Utils;
import org.mifosplatform.integrationtests.common.charges.ChargesHelper;
import org.mifosplatform.integrationtests.common.loans.LoanApplicationTestBuilder;
import org.mifosplatform.integrationtests.common.loans.LoanProductTestBuilder;
import org.mifosplatform.integrationtests.common.loans.LoanStatusChecker;
import org.mifosplatform.integrationtests.common.loans.LoanTransactionHelper;
import org.mifosplatform.integrationtests.common.savings.SavingsAccountHelper;
import org.mifosplatform.integrationtests.common.savings.SavingsProductHelper;
import org.mifosplatform.integrationtests.common.savings.SavingsStatusChecker;

import com.jayway.restassured.builder.RequestSpecBuilder;
import com.jayway.restassured.builder.ResponseSpecBuilder;
import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.specification.RequestSpecification;
import com.jayway.restassured.specification.ResponseSpecification;

@SuppressWarnings({ "unused", "unchecked", "rawtypes", "static-access", "cast" })
public class SchedulerJobsTestResults {

    private ResponseSpecification responseSpec;
    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpecForSchedulerJob;
    private SchedulerJobHelper schedulerJobHelper;
    private SavingsAccountHelper savingsAccountHelper;
    private LoanTransactionHelper loanTransactionHelper;
    private HolidayHelper holidayHelper;
    private GlobalConfigurationHelper globalConfigurationHelper;

    @Before
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.requestSpec.header("X-Mifos-Platform-TenantId", "default");
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.responseSpecForSchedulerJob = new ResponseSpecBuilder().expectStatusCode(202).build();
    }

    @Test
    public void testApplyAnnualFeeForSavingsJobOutcome() throws InterruptedException {
        this.savingsAccountHelper = new SavingsAccountHelper(this.requestSpec, this.responseSpec);
        this.schedulerJobHelper = new SchedulerJobHelper(this.requestSpec, this.responseSpec);

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(clientID);

        final Integer savingsProductID = createSavingsProduct(this.requestSpec, this.responseSpec,
                ClientSavingsIntegrationTest.MINIMUM_OPENING_BALANCE);
        Assert.assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplication(clientID, savingsProductID,
                ClientSavingsIntegrationTest.ACCOUNT_TYPE_INDIVIDUAL);
        Assert.assertNotNull(savingsProductID);

        HashMap savingsStatusHashMap = SavingsStatusChecker.getStatusOfSavings(this.requestSpec, this.responseSpec, savingsId);
        SavingsStatusChecker.verifySavingsIsPending(savingsStatusHashMap);

        final Integer annualFeeChargeId = ChargesHelper.createCharges(this.requestSpec, this.responseSpec,
                ChargesHelper.getSavingsAnnualFeeJSON());
        Assert.assertNotNull(annualFeeChargeId);

        this.savingsAccountHelper.addChargesForSavings(savingsId, annualFeeChargeId);
        ArrayList<HashMap> chargesPendingState = this.savingsAccountHelper.getSavingsCharges(savingsId);
        Assert.assertEquals(1, chargesPendingState.size());

        savingsStatusHashMap = this.savingsAccountHelper.approveSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);

        savingsStatusHashMap = this.savingsAccountHelper.activateSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        ArrayList<HashMap> allSchedulerJobsData = this.schedulerJobHelper.getAllSchedulerJobs(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(allSchedulerJobsData);

        Integer jobId = (Integer) allSchedulerJobsData.get(3).get("jobId");

        HashMap summaryBefore = this.savingsAccountHelper.getSavingsSummary(savingsId);

        // Executing Scheduler Job
        this.schedulerJobHelper.runSchedulerJob(this.requestSpec, this.responseSpecForSchedulerJob, jobId.toString());

        // Retrieving Scheduler Job by ID
        HashMap schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
        Assert.assertNotNull(schedulerJob);

        // Waiting for Job to Complete
        while ((Boolean) schedulerJob.get("currentlyRunning") == true) {
            Thread.sleep(120000);
            schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
            Assert.assertNotNull(schedulerJob);
            System.out.println("Job is Still Running");
        }

        ArrayList<HashMap> jobHistoryData = this.schedulerJobHelper.getSchedulerJobHistory(this.requestSpec, this.responseSpec,
                jobId.toString());

        // Verifying the Status of the Recently executed Scheduler Job
        Assert.assertEquals("Verifying Last Scheduler Job Status", "success", jobHistoryData.get(jobHistoryData.size() - 1).get("status"));
        final HashMap chargeData = ChargesHelper.getChargeById(this.requestSpec, this.responseSpec, annualFeeChargeId);

        Float chargeAmount = (Float) chargeData.get("amount");

        final HashMap summaryAfter = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Assert.assertEquals("Verifying Annual Fee after Running Scheduler Job for Apply Anual Fee", chargeAmount,
                (Float) summaryAfter.get("totalAnnualFees"));

    }

    @Test
    public void testInterestPostingForSavingsJobOutcome() throws InterruptedException {
        this.savingsAccountHelper = new SavingsAccountHelper(this.requestSpec, this.responseSpec);
        this.schedulerJobHelper = new SchedulerJobHelper(this.requestSpec, this.responseSpec);

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(clientID);

        final Integer savingsProductID = createSavingsProduct(this.requestSpec, this.responseSpec,
                ClientSavingsIntegrationTest.MINIMUM_OPENING_BALANCE);
        Assert.assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplication(clientID, savingsProductID,
                ClientSavingsIntegrationTest.ACCOUNT_TYPE_INDIVIDUAL);
        Assert.assertNotNull(savingsProductID);

        HashMap savingsStatusHashMap = SavingsStatusChecker.getStatusOfSavings(this.requestSpec, this.responseSpec, savingsId);
        SavingsStatusChecker.verifySavingsIsPending(savingsStatusHashMap);

        savingsStatusHashMap = this.savingsAccountHelper.approveSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);

        savingsStatusHashMap = this.savingsAccountHelper.activateSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        ArrayList<HashMap> allSchedulerJobsData = this.schedulerJobHelper.getAllSchedulerJobs(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(allSchedulerJobsData);

        Integer jobId = (Integer) allSchedulerJobsData.get(5).get("jobId");

        final HashMap summaryBefore = this.savingsAccountHelper.getSavingsSummary(savingsId);

        // Executing Scheduler Job
        this.schedulerJobHelper.runSchedulerJob(this.requestSpec, this.responseSpecForSchedulerJob, jobId.toString());

        // Retrieving Scheduler Job by ID
        HashMap schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
        Assert.assertNotNull(schedulerJob);

        // Waiting for Job to complete
        while ((Boolean) schedulerJob.get("currentlyRunning") == true) {
            Thread.sleep(120000);
            schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
            Assert.assertNotNull(schedulerJob);
            System.out.println("Job is Still Running");
        }

        ArrayList<HashMap> jobHistoryData = this.schedulerJobHelper.getSchedulerJobHistory(this.requestSpec, this.responseSpec,
                jobId.toString());

        // Verifying the Status of the Recently executed Scheduler Job
        Assert.assertEquals("Verifying Last Scheduler Job Status", "success", jobHistoryData.get(jobHistoryData.size() - 1).get("status"));

        final HashMap summaryAfter = this.savingsAccountHelper.getSavingsSummary(savingsId);

        Assert.assertNotSame("Verifying the Balance after running Post Interest for Savings Job", summaryBefore.get("accountBalance"),
                summaryAfter.get("accountBalance"));
    }

    @Test
    public void testTransferFeeForLoansFromSavingsJobOutcome() throws InterruptedException {
        this.savingsAccountHelper = new SavingsAccountHelper(this.requestSpec, this.responseSpec);
        this.schedulerJobHelper = new SchedulerJobHelper(this.requestSpec, this.responseSpec);
        this.loanTransactionHelper = new LoanTransactionHelper(this.requestSpec, this.responseSpec);

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(clientID);

        final Integer savingsProductID = createSavingsProduct(this.requestSpec, this.responseSpec,
                ClientSavingsIntegrationTest.MINIMUM_OPENING_BALANCE);
        Assert.assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplication(clientID, savingsProductID,
                ClientSavingsIntegrationTest.ACCOUNT_TYPE_INDIVIDUAL);
        Assert.assertNotNull(savingsProductID);

        HashMap savingsStatusHashMap = SavingsStatusChecker.getStatusOfSavings(this.requestSpec, this.responseSpec, savingsId);
        SavingsStatusChecker.verifySavingsIsPending(savingsStatusHashMap);

        savingsStatusHashMap = this.savingsAccountHelper.approveSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);

        savingsStatusHashMap = this.savingsAccountHelper.activateSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        final Integer loanProductID = createLoanProduct();
        Assert.assertNotNull(loanProductID);

        final Integer loanID = applyForLoanApplication(clientID.toString(), loanProductID.toString(), savingsId.toString());
        Assert.assertNotNull(loanID);

        HashMap loanStatusHashMap = LoanStatusChecker.getStatusOfLoan(this.requestSpec, this.responseSpec, loanID);
        LoanStatusChecker.verifyLoanIsPending(loanStatusHashMap);

        loanStatusHashMap = this.loanTransactionHelper.approveLoan(AccountTransferTest.LOAN_APPROVAL_DATE, loanID);
        LoanStatusChecker.verifyLoanIsApproved(loanStatusHashMap);

        Integer specifiedDueDateChargeId = ChargesHelper.createCharges(this.requestSpec, this.responseSpec,
                ChargesHelper.getLoanSpecifiedDueDateWithAccountTransferJSON());
        Assert.assertNotNull(specifiedDueDateChargeId);

        this.loanTransactionHelper.addChargesForLoan(loanID,
                LoanTransactionHelper.getSpecifiedDueDateChargesForLoanAsJSON(specifiedDueDateChargeId.toString()));
        ArrayList<HashMap> chargesPendingState = this.loanTransactionHelper.getLoanCharges(loanID);
        Assert.assertEquals(1, chargesPendingState.size());

        loanStatusHashMap = this.loanTransactionHelper.disburseLoan(AccountTransferTest.LOAN_DISBURSAL_DATE, loanID);
        LoanStatusChecker.verifyLoanIsActive(loanStatusHashMap);

        ArrayList<HashMap> allSchedulerJobsData = this.schedulerJobHelper.getAllSchedulerJobs(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(allSchedulerJobsData);

        Integer jobId = (Integer) allSchedulerJobsData.get(6).get("jobId");

        final HashMap summaryBefore = this.savingsAccountHelper.getSavingsSummary(savingsId);

        // Executing Scheduler Job
        this.schedulerJobHelper.runSchedulerJob(this.requestSpec, this.responseSpecForSchedulerJob, jobId.toString());

        // Retrieving Scheduler Job by ID
        HashMap schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
        Assert.assertNotNull(schedulerJob);

        // Waiting for Job to complete
        while ((Boolean) schedulerJob.get("currentlyRunning") == true) {
            Thread.sleep(120000);
            schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
            Assert.assertNotNull(schedulerJob);
            System.out.println("Job is Still Running");
        }

        ArrayList<HashMap> jobHistoryData = this.schedulerJobHelper.getSchedulerJobHistory(this.requestSpec, this.responseSpec,
                jobId.toString());

        // Verifying the Status of the Recently executed Scheduler Job
        Assert.assertEquals("Verifying Last Scheduler Job Status", "success", jobHistoryData.get(jobHistoryData.size() - 1).get("status"));

        final HashMap summaryAfter = this.savingsAccountHelper.getSavingsSummary(savingsId);

        final HashMap chargeData = ChargesHelper.getChargeById(this.requestSpec, this.responseSpec, specifiedDueDateChargeId);

        Float chargeAmount = (Float) chargeData.get("amount");

        final Float balance = (Float) summaryBefore.get("accountBalance") - chargeAmount;

        Assert.assertEquals("Verifying the Balance after running Transfer Fee for Loans from Savings", balance,
                (Float) summaryAfter.get("accountBalance"));

    }

    @Test
    public void testApplyHolidaysToLoansJobOutcome() throws InterruptedException {
        this.schedulerJobHelper = new SchedulerJobHelper(this.requestSpec, this.responseSpec);
        this.loanTransactionHelper = new LoanTransactionHelper(this.requestSpec, this.responseSpec);
        this.holidayHelper = new HolidayHelper(this.requestSpec, this.responseSpec);
        this.globalConfigurationHelper = new GlobalConfigurationHelper(this.requestSpec, this.responseSpec);

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(clientID);

        Integer holidayId = this.holidayHelper.createHolidays(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(holidayId);

        final Integer loanProductID = createLoanProduct();
        Assert.assertNotNull(loanProductID);

        final Integer loanID = applyForLoanApplication(clientID.toString(), loanProductID.toString(), null);
        Assert.assertNotNull(loanID);

        HashMap loanStatusHashMap = LoanStatusChecker.getStatusOfLoan(this.requestSpec, this.responseSpec, loanID);
        LoanStatusChecker.verifyLoanIsPending(loanStatusHashMap);

        loanStatusHashMap = this.loanTransactionHelper.approveLoan(AccountTransferTest.LOAN_APPROVAL_DATE, loanID);
        LoanStatusChecker.verifyLoanIsApproved(loanStatusHashMap);

        loanStatusHashMap = this.loanTransactionHelper.disburseLoan(AccountTransferTest.LOAN_DISBURSAL_DATE, loanID);
        LoanStatusChecker.verifyLoanIsActive(loanStatusHashMap);

        // Retrieving All Global Configuration details
        final ArrayList<HashMap> globalConfig = this.globalConfigurationHelper.getAllGlobalConfigurations(this.requestSpec,
                this.responseSpec);
        Assert.assertNotNull(globalConfig);

        // Updating Value for reschedule-repayments-on-holidays Global
        // Configuration
        Integer configId = (Integer) globalConfig.get(3).get("id");
        Assert.assertNotNull(configId);

        HashMap configData = this.globalConfigurationHelper.getGlobalConfigurationById(this.requestSpec, this.responseSpec,
                configId.toString());
        Assert.assertNotNull(configData);

        Boolean enabled = (Boolean) globalConfig.get(3).get("enabled");

        if (enabled == false) {
            enabled = true;
            configId = this.globalConfigurationHelper.updateEnabledFlagForGlobalConfiguration(this.requestSpec, this.responseSpec,
                    configId.toString(), enabled);
        }

        ArrayList<HashMap> allSchedulerJobsData = this.schedulerJobHelper.getAllSchedulerJobs(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(allSchedulerJobsData);

        Integer jobId = (Integer) allSchedulerJobsData.get(4).get("jobId");

        final ArrayList<HashMap> repaymentScheduleDataBeforeJob = this.loanTransactionHelper.getLoanRepaymentSchedule(this.requestSpec,
                this.responseSpec, loanID);

        holidayId = this.holidayHelper.activateHolidays(this.requestSpec, this.responseSpec, holidayId.toString());
        Assert.assertNotNull(holidayId);

        // Executing Scheduler Job
        this.schedulerJobHelper.runSchedulerJob(this.requestSpec, this.responseSpecForSchedulerJob, jobId.toString());

        // Retrieving Scheduler Job by ID
        HashMap schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
        Assert.assertNotNull(schedulerJob);

        // Waiting for Job to complete
        while ((Boolean) schedulerJob.get("currentlyRunning") == true) {
            Thread.sleep(120000);
            schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
            Assert.assertNotNull(schedulerJob);
            System.out.println("Job is Still Running");
        }

        ArrayList<HashMap> jobHistoryData = this.schedulerJobHelper.getSchedulerJobHistory(this.requestSpec, this.responseSpec,
                jobId.toString());

        // Verifying the Status of the Recently executed Scheduler Job
        Assert.assertEquals("Verifying Last Scheduler Job Status", "success", jobHistoryData.get(jobHistoryData.size() - 1).get("status"));

        final ArrayList<HashMap> repaymentScheduleDataAfterJob = this.loanTransactionHelper.getLoanRepaymentSchedule(this.requestSpec,
                this.responseSpec, loanID);

        HashMap holidayData = this.holidayHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, holidayId.toString());
        ArrayList<Integer> repaymentsRescheduledDate = (ArrayList<Integer>) holidayData.get("repaymentsRescheduledTo");

        ArrayList<Integer> rescheduleDateAfter = (ArrayList<Integer>) repaymentScheduleDataAfterJob.get(2).get("fromDate");

        Assert.assertEquals("Verifying Repayment Rescheduled Date after Running Apply Holidays to Loans Scheduler Job",
                repaymentsRescheduledDate, repaymentsRescheduledDate);

    }

    @Test
    public void testApplyDueFeeChargesForSavingsJobOutcome() throws InterruptedException {
        this.savingsAccountHelper = new SavingsAccountHelper(this.requestSpec, this.responseSpec);
        this.schedulerJobHelper = new SchedulerJobHelper(this.requestSpec, this.responseSpec);

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(clientID);

        final Integer savingsProductID = createSavingsProduct(this.requestSpec, this.responseSpec,
                ClientSavingsIntegrationTest.MINIMUM_OPENING_BALANCE);
        Assert.assertNotNull(savingsProductID);

        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplication(clientID, savingsProductID,
                ClientSavingsIntegrationTest.ACCOUNT_TYPE_INDIVIDUAL);
        Assert.assertNotNull(savingsProductID);

        HashMap savingsStatusHashMap = SavingsStatusChecker.getStatusOfSavings(this.requestSpec, this.responseSpec, savingsId);
        SavingsStatusChecker.verifySavingsIsPending(savingsStatusHashMap);

        final Integer specifiedDueDateChargeId = ChargesHelper.createCharges(this.requestSpec, this.responseSpec,
                ChargesHelper.getSavingsSpecifiedDueDateJSON());
        Assert.assertNotNull(specifiedDueDateChargeId);

        this.savingsAccountHelper.addChargesForSavings(savingsId, specifiedDueDateChargeId);
        ArrayList<HashMap> chargesPendingState = this.savingsAccountHelper.getSavingsCharges(savingsId);
        Assert.assertEquals(1, chargesPendingState.size());

        savingsStatusHashMap = this.savingsAccountHelper.approveSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsApproved(savingsStatusHashMap);

        savingsStatusHashMap = this.savingsAccountHelper.activateSavings(savingsId);
        SavingsStatusChecker.verifySavingsIsActive(savingsStatusHashMap);

        ArrayList<HashMap> allSchedulerJobsData = this.schedulerJobHelper.getAllSchedulerJobs(this.requestSpec, this.responseSpec);
        Assert.assertNotNull(allSchedulerJobsData);

        Integer jobId = (Integer) allSchedulerJobsData.get(7).get("jobId");

        HashMap schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
        Assert.assertNotNull(schedulerJob);

        HashMap summaryBefore = this.savingsAccountHelper.getSavingsSummary(savingsId);

        // Executing Scheduler Job
        this.schedulerJobHelper.runSchedulerJob(this.requestSpec, this.responseSpecForSchedulerJob, jobId.toString());

        schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
        Assert.assertNotNull(schedulerJob);

        // Waiting for Job to complete
        while ((Boolean) schedulerJob.get("currentlyRunning") == true) {
            Thread.sleep(120000);
            schedulerJob = this.schedulerJobHelper.getSchedulerJobById(this.requestSpec, this.responseSpec, jobId.toString());
            Assert.assertNotNull(schedulerJob);
            System.out.println("Job is Still Running");
        }

        ArrayList<HashMap> jobHistoryData = this.schedulerJobHelper.getSchedulerJobHistory(this.requestSpec, this.responseSpec,
                jobId.toString());

        // Verifying the Status of the Recently executed Scheduler Job
        Assert.assertEquals("Verifying Last Scheduler Job Status", "success", jobHistoryData.get(jobHistoryData.size() - 1).get("status"));

        HashMap summaryAfter = this.savingsAccountHelper.getSavingsSummary(savingsId);

        final HashMap chargeData = ChargesHelper.getChargeById(this.requestSpec, this.responseSpec, specifiedDueDateChargeId);

        Float chargeAmount = (Float) chargeData.get("amount");

        final Float balance = (Float) summaryBefore.get("accountBalance") - chargeAmount;

        Assert.assertEquals("Verifying the Balance after running Pay due Savings Charges", balance,
                (Float) summaryAfter.get("accountBalance"));
    }

    private Integer createSavingsProduct(final RequestSpecification requestSpec, final ResponseSpecification responseSpec,
            final String minOpenningBalance) {
        System.out.println("------------------------------CREATING NEW SAVINGS PRODUCT ---------------------------------------");
        SavingsProductHelper savingsProductHelper = new SavingsProductHelper();
        final String savingsProductJSON = savingsProductHelper //
                .withInterestCompoundingPeriodTypeAsDaily() //
                .withInterestPostingPeriodTypeAsMonthly() //
                .withInterestCalculationPeriodTypeAsDailyBalance() //
                .withMinimumOpenningBalance(minOpenningBalance).build();
        return SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
    }

    private Integer createLoanProduct() {
        System.out.println("------------------------------CREATING NEW LOAN PRODUCT ---------------------------------------");
        final String loanProductJSON = new LoanProductTestBuilder() //
                .withPrincipal("15,000.00") //
                .withNumberOfRepayments("4") //
                .withRepaymentAfterEvery("1") //
                .withRepaymentTypeAsMonth() //
                .withinterestRatePerPeriod("1") //
                .withInterestRateFrequencyTypeAsMonths() //
                .withAmortizationTypeAsEqualInstallments() //
                .withInterestTypeAsDecliningBalance() //
                .build();
        return this.loanTransactionHelper.getLoanProductId(loanProductJSON);
    }

    private Integer applyForLoanApplication(final String clientID, final String loanProductID, final String savingsID) {
        System.out.println("--------------------------------APPLYING FOR LOAN APPLICATION--------------------------------");
        final String loanApplicationJSON = new LoanApplicationTestBuilder() //
                .withPrincipal("15,000.00") //
                .withLoanTermFrequency("4") //
                .withLoanTermFrequencyAsMonths() //
                .withNumberOfRepayments("4") //
                .withRepaymentEveryAfter("1") //
                .withRepaymentFrequencyTypeAsMonths() //
                .withInterestRatePerPeriod("2") //
                .withAmortizationTypeAsEqualInstallments() //
                .withInterestTypeAsDecliningBalance() //
                .withInterestCalculationPeriodTypeSameAsRepaymentPeriod() //
                .withExpectedDisbursementDate("10 January 2013") //
                .withSubmittedOnDate("10 January 2013") //
                .build(clientID, loanProductID, savingsID);
        return this.loanTransactionHelper.getLoanId(loanApplicationJSON);
    }
}

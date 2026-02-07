/**
 * Corporate Governance SDK Types
 *
 * TypeScript models for OttoChain corporate governance state machines.
 * Covers entities, boards, officers, shareholders, and compliance.
 *
 * @packageDocumentation
 */

// =============================================================================
// JSON State Machine Definitions
// =============================================================================

import CorporateEntityDefinition from './corporate/corporate-entity.json';
import CorporateBoardDefinition from './corporate/corporate-board.json';
import CorporateShareholdersDefinition from './corporate/corporate-shareholders.json';
import CorporateOfficersDefinition from './corporate/corporate-officers.json';
import CorporateBylawsDefinition from './corporate/corporate-bylaws.json';
import CorporateCommitteeDefinition from './corporate/corporate-committee.json';
import CorporateResolutionDefinition from './corporate/corporate-resolution.json';
import CorporateProxyDefinition from './corporate/corporate-proxy.json';
import CorporateSecuritiesDefinition from './corporate/corporate-securities.json';
import CorporateComplianceDefinition from './corporate/corporate-compliance.json';

/**
 * All corporate governance state machine definitions
 */
export const CORPORATE_DEFINITIONS = {
  'corporate-entity': CorporateEntityDefinition,
  'corporate-board': CorporateBoardDefinition,
  'corporate-shareholders': CorporateShareholdersDefinition,
  'corporate-officers': CorporateOfficersDefinition,
  'corporate-bylaws': CorporateBylawsDefinition,
  'corporate-committee': CorporateCommitteeDefinition,
  'corporate-resolution': CorporateResolutionDefinition,
  'corporate-proxy': CorporateProxyDefinition,
  'corporate-securities': CorporateSecuritiesDefinition,
  'corporate-compliance': CorporateComplianceDefinition,
} as const;

export type CorporateDefinitionType = keyof typeof CORPORATE_DEFINITIONS;

// =============================================================================
// Type Enums
// =============================================================================

/**
 * Legal entity types for corporate formation
 */
export enum CorporateEntityType {
  C_CORP = 'C_CORP',
  S_CORP = 'S_CORP',
  B_CORP = 'B_CORP',
  LLC = 'LLC',
  LP = 'LP',
  LLP = 'LLP',
}

/**
 * Executive officer roles
 */
export enum OfficerRole {
  CEO = 'CEO',
  PRESIDENT = 'PRESIDENT',
  COO = 'COO',
  CFO = 'CFO',
  CTO = 'CTO',
  CLO = 'CLO',
  SECRETARY = 'SECRETARY',
  TREASURER = 'TREASURER',
  VP = 'VP',
  SVP = 'SVP',
  EVP = 'EVP',
  GENERAL_COUNSEL = 'GENERAL_COUNSEL',
  CONTROLLER = 'CONTROLLER',
  ASSISTANT_SECRETARY = 'ASSISTANT_SECRETARY',
  ASSISTANT_TREASURER = 'ASSISTANT_TREASURER',
  OTHER = 'OTHER',
}

/**
 * Board committee types
 */
export enum CommitteeType {
  AUDIT = 'AUDIT',
  COMPENSATION = 'COMPENSATION',
  NOMINATING_GOVERNANCE = 'NOMINATING_GOVERNANCE',
  EXECUTIVE = 'EXECUTIVE',
  RISK = 'RISK',
  FINANCE = 'FINANCE',
  SPECIAL = 'SPECIAL',
  OTHER = 'OTHER',
}

/**
 * Share classes for equity
 */
export enum ShareClass {
  COMMON = 'COMMON',
  PREFERRED_A = 'PREFERRED_A',
  PREFERRED_B = 'PREFERRED_B',
  PREFERRED_C = 'PREFERRED_C',
  PREFERRED_SEED = 'PREFERRED_SEED',
  FOUNDERS = 'FOUNDERS',
}

/**
 * Resolution types for formal corporate actions
 */
export enum ResolutionType {
  BOARD_RESOLUTION = 'BOARD_RESOLUTION',
  SHAREHOLDER_RESOLUTION = 'SHAREHOLDER_RESOLUTION',
  BOARD_WRITTEN_CONSENT = 'BOARD_WRITTEN_CONSENT',
  SHAREHOLDER_WRITTEN_CONSENT = 'SHAREHOLDER_WRITTEN_CONSENT',
  UNANIMOUS_WRITTEN_CONSENT = 'UNANIMOUS_WRITTEN_CONSENT',
}

/**
 * Resolution category for classification
 */
export enum ResolutionCategory {
  OFFICER_APPOINTMENT = 'OFFICER_APPOINTMENT',
  OFFICER_REMOVAL = 'OFFICER_REMOVAL',
  STOCK_ISSUANCE = 'STOCK_ISSUANCE',
  DIVIDEND_DECLARATION = 'DIVIDEND_DECLARATION',
  CONTRACT_APPROVAL = 'CONTRACT_APPROVAL',
  BANKING = 'BANKING',
  CHARTER_AMENDMENT = 'CHARTER_AMENDMENT',
  BYLAW_AMENDMENT = 'BYLAW_AMENDMENT',
  MERGER_ACQUISITION = 'MERGER_ACQUISITION',
  DISSOLUTION = 'DISSOLUTION',
  COMMITTEE_ACTION = 'COMMITTEE_ACTION',
  COMPENSATION = 'COMPENSATION',
  AUDIT = 'AUDIT',
  GENERAL = 'GENERAL',
  OTHER = 'OTHER',
}

/**
 * Director class for staggered boards
 */
export enum DirectorClass {
  CLASS_I = 'CLASS_I',
  CLASS_II = 'CLASS_II',
  CLASS_III = 'CLASS_III',
  UNCLASSIFIED = 'UNCLASSIFIED',
}

/**
 * Officer status
 */
export enum OfficerStatus {
  ACTIVE = 'ACTIVE',
  RESIGNED = 'RESIGNED',
  REMOVED = 'REMOVED',
  INTERIM = 'INTERIM',
}

/**
 * Director status
 */
export enum DirectorStatus {
  ACTIVE = 'ACTIVE',
  RESIGNED = 'RESIGNED',
  REMOVED = 'REMOVED',
  TERM_EXPIRED = 'TERM_EXPIRED',
}

/**
 * Securities form type
 */
export enum SecuritiesForm {
  CERTIFICATED = 'CERTIFICATED',
  BOOK_ENTRY = 'BOOK_ENTRY',
  DRS = 'DRS',
}

/**
 * Antidilution protection types
 */
export enum AntidilutionType {
  NONE = 'NONE',
  BROAD_BASED = 'BROAD_BASED',
  NARROW_BASED = 'NARROW_BASED',
  FULL_RATCHET = 'FULL_RATCHET',
}

/**
 * Quorum rule types
 */
export enum QuorumType {
  MAJORITY = 'MAJORITY',
  SUPERMAJORITY = 'SUPERMAJORITY',
  FIXED_NUMBER = 'FIXED_NUMBER',
}

/**
 * Vote threshold types
 */
export enum VoteThreshold {
  PLURALITY = 'PLURALITY',
  MAJORITY_CAST = 'MAJORITY_CAST',
  MAJORITY_OUTSTANDING = 'MAJORITY_OUTSTANDING',
  SUPERMAJORITY = 'SUPERMAJORITY',
  UNANIMOUS = 'UNANIMOUS',
}

/**
 * Proxy types
 */
export enum ProxyType {
  SPECIFIC_MEETING = 'SPECIFIC_MEETING',
  GENERAL = 'GENERAL',
  LIMITED = 'LIMITED',
}

/**
 * Compliance status colors
 */
export enum ComplianceStatus {
  GREEN = 'GREEN',
  YELLOW = 'YELLOW',
  RED = 'RED',
}

/**
 * Filing types for compliance calendar
 */
export enum FilingType {
  ANNUAL_REPORT = 'ANNUAL_REPORT',
  BIENNIAL_REPORT = 'BIENNIAL_REPORT',
  FRANCHISE_TAX = 'FRANCHISE_TAX',
  STATEMENT_OF_INFORMATION = 'STATEMENT_OF_INFORMATION',
  REGISTERED_AGENT_UPDATE = 'REGISTERED_AGENT_UPDATE',
  FOREIGN_QUALIFICATION_ANNUAL = 'FOREIGN_QUALIFICATION_ANNUAL',
  BENEFICIAL_OWNERSHIP = 'BENEFICIAL_OWNERSHIP',
  FEDERAL_TAX_RETURN = 'FEDERAL_TAX_RETURN',
  STATE_TAX_RETURN = 'STATE_TAX_RETURN',
  SEC_FILING = 'SEC_FILING',
  OTHER = 'OTHER',
}

/**
 * Deficiency types for compliance issues
 */
export enum DeficiencyType {
  ANNUAL_REPORT_MISSING = 'ANNUAL_REPORT_MISSING',
  FRANCHISE_TAX_DELINQUENT = 'FRANCHISE_TAX_DELINQUENT',
  REGISTERED_AGENT_LAPSE = 'REGISTERED_AGENT_LAPSE',
  BENEFICIAL_OWNERSHIP_MISSING = 'BENEFICIAL_OWNERSHIP_MISSING',
  OTHER = 'OTHER',
}

// =============================================================================
// Config Interfaces
// =============================================================================

/**
 * Share class configuration
 */
export interface ShareClassConfig {
  classId: string;
  className: string;
  authorized: number;
  issued: number;
  outstanding: number;
  treasury: number;
  parValue: number;
  votingRights: boolean;
  votesPerShare: number;
  liquidationPreference?: number;
  dividendRate?: number;
  convertible: boolean;
  conversionRatio?: number;
  antidilution?: AntidilutionType;
}

/**
 * Director seat configuration
 */
export interface DirectorSeat {
  directorId: string;
  name: string;
  email?: string;
  termStart: string;
  termEnd: string;
  class: DirectorClass;
  status: DirectorStatus;
  isIndependent: boolean;
  isChair: boolean;
  isLeadIndependent: boolean;
  committees: string[];
  electedBy: string;
  compensationAgreementRef?: string;
}

/**
 * Officer configuration
 */
export interface OfficerConfig {
  officerId: string;
  personId: string;
  name: string;
  title: OfficerRole;
  customTitle?: string;
  status: OfficerStatus;
  appointedDate: string;
  appointmentResolutionRef: string;
  terminationDate?: string;
  reportsTo?: string;
  isBoardMember: boolean;
  directorId?: string;
  compensationAgreementRef?: string;
  authorityLevel: 'FULL' | 'LIMITED' | 'SPECIFIC';
  spendingLimit?: number;
  signatureAuthority?: SignatureAuthority;
  delegatedAuthorities: DelegatedAuthority[];
}

/**
 * Signature authority configuration
 */
export interface SignatureAuthority {
  contracts: boolean;
  contractLimit?: number;
  checks: boolean;
  checkLimit?: number;
  hiringAuthority: boolean;
  terminationAuthority: boolean;
}

/**
 * Delegated authority record
 */
export interface DelegatedAuthority {
  delegationId: string;
  authority: string;
  scope: string;
  delegatedBy: string;
  delegatedOn: string;
  expiresOn?: string;
  revoked: boolean;
}

/**
 * Committee charter configuration
 */
export interface CommitteeCharter {
  charterId: string;
  version: string;
  adoptedDate: string;
  lastReviewedDate: string;
  documentRef: string;
  purposes: string[];
  responsibilities: string[];
  authorityLimits?: Record<string, unknown>;
  reportingRequirements?: string;
}

/**
 * Committee membership requirements
 */
export interface CommitteeMembershipRequirements {
  minimumMembers: number;
  maximumMembers?: number;
  independenceRequired: boolean;
  financialExpertRequired: boolean;
  independenceStandard?: 'NYSE' | 'NASDAQ' | 'SEC' | 'CUSTOM';
}

/**
 * Quorum rules configuration
 */
export interface QuorumRules {
  type: QuorumType;
  threshold: number;
  minimumRequired?: number;
}

/**
 * Voting rules configuration
 */
export interface VotingRules {
  standardApproval: 'MAJORITY_PRESENT' | 'MAJORITY_FULL_BOARD';
  supermajorityMatters: string[];
  supermajorityThreshold: number;
}

// =============================================================================
// State Interfaces
// =============================================================================

/**
 * Address structure
 */
export interface Address {
  street?: string;
  city?: string;
  state?: string;
  zip?: string;
  country?: string;
}

/**
 * Jurisdiction configuration
 */
export interface Jurisdiction {
  state: string;
  country: string;
  foreignQualifications: string[];
}

/**
 * Registered agent configuration
 */
export interface RegisteredAgent {
  name: string;
  address: Address;
  phone?: string;
  email?: string;
  effectiveDate: string;
}

/**
 * Share structure configuration
 */
export interface ShareStructure {
  classes: ShareClassConfig[];
  totalAuthorized: number;
  totalIssued: number;
  totalOutstanding: number;
}

/**
 * Charter amendment record
 */
export interface CharterAmendment {
  amendmentId: string;
  description: string;
  effectiveDate: string;
  resolutionRef: string;
  filedDate: string;
}

/**
 * Corporate entity state
 */
export interface CorporateEntityState {
  entityId: string;
  legalName: string;
  tradeName?: string;
  entityType: CorporateEntityType;
  jurisdiction: Jurisdiction;
  formationDate?: string;
  fiscalYearEnd: string;
  registeredAgent: RegisteredAgent;
  principalOffice: Address;
  shareStructure: ShareStructure;
  incorporators: Array<{
    name: string;
    address: Address;
    signatureDate: string;
  }>;
  ein?: string;
  stateIds: Record<string, string>;
  suspensionReason?: string;
  suspensionDate?: string;
  dissolutionDate?: string;
  dissolutionReason?: string;
  charterAmendments: CharterAmendment[];
  createdAt: string;
  updatedAt: string;
}

/**
 * Board meeting record
 */
export interface BoardMeeting {
  meetingId: string;
  type: 'REGULAR' | 'SPECIAL' | 'ANNUAL' | 'ORGANIZATIONAL';
  scheduledDate: string;
  location?: string;
  isVirtual: boolean;
  calledBy: string;
  noticeDate: string;
  agenda: string[];
  attendees: Array<{
    directorId: string;
    present: boolean;
    arrivedAt?: string;
    departedAt?: string;
    viaProxy: boolean;
  }>;
  quorumPresent: boolean;
  quorumCount: number;
  openedAt?: string;
  closedAt?: string;
  minutesRef?: string;
}

/**
 * Board seats configuration
 */
export interface BoardSeats {
  authorized: number;
  filled: number;
  vacant: number;
}

/**
 * Board structure configuration
 */
export interface BoardStructure {
  isClassified: boolean;
  termYears: number;
  classTerms?: {
    CLASS_I?: number;
    CLASS_II?: number;
    CLASS_III?: number;
  };
}

/**
 * Board state
 */
export interface BoardState {
  boardId: string;
  entityId: string;
  directors: DirectorSeat[];
  seats: BoardSeats;
  boardStructure: BoardStructure;
  quorumRules: QuorumRules;
  votingRules: VotingRules;
  currentMeeting?: BoardMeeting;
  meetingHistory: Array<{
    meetingId: string;
    type: string;
    date: string;
    quorumAchieved: boolean;
    attendeeCount: number;
    resolutionsPassed: string[];
    minutesRef: string;
  }>;
  createdAt: string;
  updatedAt: string;
}

/**
 * Shareholder record
 */
export interface ShareholderRecord {
  shareholderId: string;
  name: string;
  shareholdings: Array<{
    shareClass: string;
    shares: number;
    votes: number;
  }>;
  totalVotes: number;
  proxyGrantedTo?: string;
  hasVoted: boolean;
}

/**
 * Agenda item for shareholder meeting
 */
export interface ShareholderAgendaItem {
  itemId: string;
  itemNumber: number;
  title: string;
  description?: string;
  type: 'DIRECTOR_ELECTION' | 'AUDITOR_RATIFICATION' | 'SAY_ON_PAY' | 'CHARTER_AMENDMENT' | 'MERGER' | 'STOCK_PLAN' | 'SHAREHOLDER_PROPOSAL' | 'OTHER';
  voteRequired: VoteThreshold;
  supermajorityThreshold?: number;
  eligibleClasses?: string[];
  allowCumulativeVoting: boolean;
  status: 'PENDING' | 'VOTING' | 'CLOSED' | 'APPROVED' | 'REJECTED';
}

/**
 * Vote tally record
 */
export interface VoteTally {
  agendaItemId: string;
  forVotes: number;
  againstVotes: number;
  abstainVotes: number;
  withholdVotes: number;
  brokerNonVotes: number;
  candidateVotes?: Record<string, number>;
  result: 'APPROVED' | 'REJECTED' | 'PENDING';
  certified: boolean;
}

/**
 * Shareholder meeting state
 */
export interface ShareholderMeetingState {
  meetingId: string;
  entityId: string;
  meetingType: 'ANNUAL' | 'SPECIAL';
  fiscalYear?: number;
  scheduledDate: string;
  location: {
    physical?: string;
    virtualUrl?: string;
    isHybrid: boolean;
  };
  calledBy: {
    type: 'BOARD' | 'SHAREHOLDERS' | 'COURT';
    resolutionRef?: string;
    shareholderPetitionRef?: string;
    courtOrderRef?: string;
  };
  noticeInfo?: {
    noticeSentDate: string;
    noticeMethod: 'MAIL' | 'EMAIL' | 'ELECTRONIC_ACCESS';
    minimumNoticeDays: number;
    maximumNoticeDays: number;
  };
  recordDate?: {
    date: string;
    setByBoardOn: string;
    resolutionRef: string;
  };
  eligibleVoters: ShareholderRecord[];
  quorumRequirements: {
    type: 'SHARES_REPRESENTED' | 'SHARES_OUTSTANDING';
    threshold: number;
    sharesRequired: number;
    sharesRepresented: number;
    quorumMet: boolean;
  };
  agenda: ShareholderAgendaItem[];
  proxyPeriod?: {
    startDate: string;
    endDate: string;
    proxyMaterials: {
      proxyStatementRef: string;
      formOfProxyRef: string;
      annualReportRef?: string;
    };
  };
  votes: Array<{
    voteId: string;
    agendaItemId: string;
    voterId: string;
    shareholderId: string;
    shareClass: string;
    votesFor: number;
    votesAgainst: number;
    votesAbstain: number;
    votesWithhold: number;
    cumulativeVoteAllocation?: Record<string, number>;
    viaProxy: boolean;
    timestamp: string;
  }>;
  voteTallies: VoteTally[];
  inspectorOfElections?: {
    name: string;
    company?: string;
    appointedBy: string;
    appointmentDate: string;
  };
  sessionInfo?: {
    openedAt: string;
    chairPerson: string;
    secretaryPresent: string;
    pollsOpenedAt?: string;
    pollsClosedAt?: string;
    adjournedAt?: string;
    minutesRef?: string;
  };
  certification?: {
    certifiedAt: string;
    certifiedBy: string;
    certificateRef: string;
  };
  createdAt: string;
  updatedAt: string;
}

/**
 * Officer state
 */
export interface OfficerState {
  officersInstanceId: string;
  entityId: string;
  officers: OfficerConfig[];
  appointmentAuthority: {
    boardAppoints: OfficerRole[];
    ceoAppoints: OfficerRole[];
    cfoAppoints: OfficerRole[];
    secretaryAppoints: OfficerRole[];
  };
  successionPlan: Array<{
    position: string;
    currentOfficerId: string;
    successors: Array<{
      personId: string;
      name: string;
      priority: number;
      readiness: 'READY_NOW' | 'READY_1_YEAR' | 'READY_2_YEARS' | 'DEVELOPMENTAL';
    }>;
  }>;
  vacantPositions: OfficerRole[];
  createdAt: string;
  updatedAt: string;
}

/**
 * Bylaw section
 */
export interface BylawSection {
  sectionId: string;
  sectionNumber: string;
  title: string;
  content: string;
  amendmentRequirement: 'BOARD_ONLY' | 'BOARD_OR_SHAREHOLDERS' | 'SHAREHOLDERS_ONLY' | 'SUPERMAJORITY_SHAREHOLDERS';
  supermajorityThreshold?: number;
  lastModifiedVersion: string;
}

/**
 * Key bylaw provisions summary
 */
export interface KeyBylawProvisions {
  boardSize?: {
    minimum: number;
    maximum: number;
    sectionRef: string;
  };
  quorumRequirements?: {
    boardQuorum: number;
    shareholderQuorum: number;
    sectionRef: string;
  };
  meetingNotice?: {
    annualMeetingNotice: number;
    specialMeetingNotice: number;
    boardMeetingNotice: number;
    sectionRef: string;
  };
  indemnification?: {
    directorsIndemnified: boolean;
    officersIndemnified: boolean;
    mandatory: boolean;
    advancementOfExpenses: boolean;
    sectionRef: string;
  };
  specialMeetingThreshold?: {
    boardCanCall: boolean;
    shareholderThreshold: number;
    sectionRef: string;
  };
}

/**
 * Pending bylaw amendment
 */
export interface PendingBylawAmendment {
  amendmentId: string;
  description: string;
  proposedBy: string;
  proposedDate: string;
  sectionsAffected: string[];
  proposedChanges: Array<{
    sectionId: string;
    changeType: 'MODIFY' | 'ADD' | 'DELETE';
    currentContent?: string;
    proposedContent?: string;
  }>;
  approvalRequired: string;
  boardApprovalRef?: string;
  shareholderApprovalRef?: string;
  status: 'PROPOSED' | 'BOARD_APPROVED' | 'SHAREHOLDER_APPROVED' | 'REJECTED';
}

/**
 * Bylaws state
 */
export interface BylawsState {
  bylawsId: string;
  entityId: string;
  currentVersion: string;
  originalAdoptionDate: string;
  lastAmendedDate?: string;
  documentRef: string;
  sections: BylawSection[];
  keyProvisions: KeyBylawProvisions;
  pendingAmendment?: PendingBylawAmendment;
  amendmentHistory: Array<{
    amendmentId: string;
    version: string;
    description: string;
    sectionsAffected: string[];
    effectiveDate: string;
    approvedBy: 'BOARD' | 'SHAREHOLDERS' | 'BOTH';
    boardResolutionRef?: string;
    shareholderResolutionRef?: string;
    documentRef: string;
  }>;
  createdAt: string;
  updatedAt: string;
}

/**
 * Committee member
 */
export interface CommitteeMember {
  memberId: string;
  directorId: string;
  name: string;
  role: 'CHAIR' | 'MEMBER';
  appointedDate: string;
  appointmentResolutionRef: string;
  removedDate?: string;
  isIndependent: boolean;
  isFinancialExpert: boolean;
  status: 'ACTIVE' | 'REMOVED' | 'RESIGNED';
}

/**
 * Committee state
 */
export interface CommitteeState {
  committeeId: string;
  entityId: string;
  boardId: string;
  name: string;
  committeeType: CommitteeType;
  purpose?: string;
  isStanding: boolean;
  createdDate: string;
  disbandDate?: string;
  charter?: CommitteeCharter;
  membershipRequirements: CommitteeMembershipRequirements;
  members: CommitteeMember[];
  quorumRules: QuorumRules;
  currentMeeting?: {
    meetingId: string;
    scheduledDate: string;
    agenda: string[];
    attendees: string[];
    quorumPresent: boolean;
    openedAt?: string;
    closedAt?: string;
  };
  meetingHistory: Array<{
    meetingId: string;
    date: string;
    attendeeCount: number;
    minutesRef: string;
    actionsApproved: string[];
  }>;
  annualReviewDate?: string;
  status: 'ACTIVE' | 'DISBANDED';
  createdAt: string;
  updatedAt: string;
}

/**
 * Vote record
 */
export interface VoteRecord {
  voterId: string;
  voterName: string;
  voterType: 'DIRECTOR' | 'SHAREHOLDER' | 'COMMITTEE_MEMBER';
  vote: 'FOR' | 'AGAINST' | 'ABSTAIN' | 'RECUSE';
  votingPower: number;
  timestamp: string;
  comment?: string;
}

/**
 * Approval requirements
 */
export interface ApprovalRequirements {
  approverType: 'BOARD' | 'SHAREHOLDERS' | 'COMMITTEE' | 'BOARD_AND_SHAREHOLDERS';
  threshold: 'MAJORITY_PRESENT' | 'MAJORITY_FULL' | 'SUPERMAJORITY' | 'UNANIMOUS';
  supermajorityPercent?: number;
  quorumRequired: boolean;
  shareholderClassVotes?: string[];
}

/**
 * Resolution state
 */
export interface ResolutionState {
  resolutionId: string;
  entityId: string;
  resolutionNumber: string;
  title: string;
  resolutionType: ResolutionType;
  category: ResolutionCategory;
  proposedDate?: string;
  proposedBy?: {
    type: 'DIRECTOR' | 'OFFICER' | 'SHAREHOLDER' | 'COMMITTEE';
    personId: string;
    name: string;
  };
  resolvedText?: string;
  whereAsClauses: string[];
  resolvedClauses: string[];
  attachments: Array<{
    attachmentId: string;
    title: string;
    documentRef: string;
    type: string;
  }>;
  approvalRequirements: ApprovalRequirements;
  meetingRef?: {
    meetingType: 'BOARD_MEETING' | 'SHAREHOLDER_MEETING' | 'COMMITTEE_MEETING';
    meetingId: string;
    meetingDate: string;
  };
  voting: {
    votingOpenedAt?: string;
    votingClosedAt?: string;
    votes: VoteRecord[];
    tally: {
      for: number;
      against: number;
      abstain: number;
      recused: number;
      totalEligible?: number;
      totalVoted: number;
    };
  };
  writtenConsent?: {
    consentForm: string;
    circulationDate: string;
    consentDeadline: string;
    consents: Array<{
      consentorId: string;
      consentorName: string;
      signedDate: string;
      votingPower: number;
      signatureRef: string;
    }>;
    totalConsentPower: number;
    requiredConsentPower: number;
  };
  approvalDetails?: {
    approved: boolean;
    approvalDate: string;
    approvalMethod: 'MEETING_VOTE' | 'WRITTEN_CONSENT';
    certifiedBy: string;
    certificationDate: string;
  };
  effectiveDate?: string;
  expirationDate?: string;
  executionDetails?: {
    executedDate: string;
    executedBy: string;
    executionNotes?: string;
    resultingActions: Array<{
      actionType: string;
      reference: string;
      completedDate: string;
    }>;
  };
  rescissionDetails?: {
    rescindedDate: string;
    rescindingResolutionRef: string;
    reason: string;
  };
  relatedResolutions: Array<{
    resolutionId: string;
    relationship: 'SUPERSEDES' | 'SUPERSEDED_BY' | 'AMENDS' | 'AMENDED_BY' | 'DEPENDS_ON' | 'ENABLES';
  }>;
  createdAt: string;
  updatedAt: string;
}

/**
 * Proxy scope configuration
 */
export interface ProxyScope {
  meetingId?: string;
  meetingDate?: string;
  agendaItems?: string[];
  votingInstructions?: Array<{
    agendaItemId: string;
    instruction: 'FOR' | 'AGAINST' | 'ABSTAIN' | 'DISCRETIONARY';
    cumulativeAllocation?: Record<string, number>;
  }>;
  discretionaryAuthority: boolean;
}

/**
 * Proxy card details
 */
export interface ProxyCard {
  cardId: string;
  format: 'PAPER' | 'ELECTRONIC' | 'TELEPHONE';
  signedDate: string;
  signatureVerified: boolean;
  documentRef: string;
  controlNumber: string;
}

/**
 * Proxy state
 */
export interface ProxyState {
  proxyId: string;
  entityId: string;
  grantorId: string;
  grantorName: string;
  grantorShares: Array<{
    shareClass: string;
    shares: number;
    votes: number;
    certificateNumbers?: string[];
  }>;
  totalVotes: number;
  holderId: string;
  holderName: string;
  holderType: 'INDIVIDUAL' | 'MANAGEMENT' | 'INSTITUTIONAL' | 'PROXY_SOLICITOR';
  proxyType: ProxyType;
  scope: ProxyScope;
  grantDate: string;
  effectiveDate: string;
  expirationDate: string;
  revocability: {
    isRevocable: boolean;
    irrevocableReason?: 'COUPLED_WITH_INTEREST' | 'VOTING_AGREEMENT' | 'PLEDGE';
    irrevocableUntil?: string;
  };
  proxyCard?: ProxyCard;
  votesExercised: Array<{
    meetingId: string;
    agendaItemId: string;
    voteCast: 'FOR' | 'AGAINST' | 'ABSTAIN' | 'WITHHOLD';
    voteCount: number;
    votedAt: string;
    votedBy: string;
  }>;
  revocationDetails?: {
    revokedAt: string;
    revokedBy: string;
    revocationMethod: 'WRITTEN_REVOCATION' | 'LATER_PROXY' | 'ATTENDANCE_IN_PERSON' | 'DEATH_INCAPACITY';
    supersedingProxyId?: string;
  };
  createdAt: string;
  updatedAt: string;
}

/**
 * Securities holder
 */
export interface SecuritiesHolder {
  holderId: string;
  holderType: 'INDIVIDUAL' | 'ENTITY' | 'TRUST' | 'TREASURY';
  name: string;
  taxId?: string;
  address?: Address;
  acquisitionDate: string;
  acquisitionMethod: 'ORIGINAL_ISSUANCE' | 'PURCHASE' | 'GIFT' | 'INHERITANCE' | 'STOCK_SPLIT' | 'CONVERSION' | 'EXERCISE';
  costBasis?: number;
}

/**
 * Securities restrictions
 */
export interface SecuritiesRestrictions {
  isRestricted: boolean;
  restrictionType: Array<'RULE_144' | 'SECTION_4(a)(2)' | 'REG_D' | 'REG_S' | 'LOCK_UP' | 'VESTING' | 'RIGHT_OF_FIRST_REFUSAL'>;
  restrictionEndDate?: string;
  legends: string[];
  vestingSchedule?: {
    vestingStartDate: string;
    totalShares: number;
    vestedShares: number;
    vestingScheduleRef: string;
  };
  lockUpExpiration?: string;
  rofr?: {
    holderIds: string[];
    noticePeriodDays: number;
  };
}

/**
 * Corporate action record
 */
export interface CorporateAction {
  actionId: string;
  actionType: 'STOCK_SPLIT' | 'REVERSE_SPLIT' | 'STOCK_DIVIDEND' | 'CONVERSION' | 'RECLASSIFICATION';
  actionDate: string;
  ratio?: string;
  sharesBeforeAction: number;
  sharesAfterAction: number;
  resolutionRef: string;
}

/**
 * Securities state
 */
export interface SecuritiesState {
  securityId: string;
  entityId: string;
  shareClass: string;
  shareClassName: string;
  certificateNumber?: string;
  cusip?: string;
  shareCount: number;
  parValue: number;
  issuancePrice?: number;
  issuanceDate?: string;
  form: SecuritiesForm;
  holder?: SecuritiesHolder;
  restrictions: SecuritiesRestrictions;
  authorization?: {
    authorizedDate: string;
    charterProvision?: string;
    authorizedShares: number;
  };
  issuanceDetails?: {
    boardResolutionRef: string;
    issuanceAgreementRef?: string;
    consideration: {
      type: 'CASH' | 'PROPERTY' | 'SERVICES' | 'DEBT_CONVERSION' | 'STOCK_CONVERSION';
      value: number;
      description?: string;
    };
    exemptionUsed?: string;
    accreditedInvestor?: boolean;
  };
  transferHistory: Array<{
    transferId: string;
    transferDate: string;
    fromHolderId: string;
    toHolderId: string;
    shares: number;
    transferType: 'SALE' | 'GIFT' | 'INHERITANCE' | 'INTERNAL';
    pricePerShare?: number;
    transferAgentConfirmation?: string;
  }>;
  corporateActions: CorporateAction[];
  retirementDetails?: {
    retiredDate: string;
    retirementMethod: 'REPURCHASE' | 'REDEMPTION' | 'CANCELLATION' | 'CONVERSION';
    repurchasePrice?: number;
    boardResolutionRef: string;
  };
  createdAt: string;
  updatedAt: string;
}

/**
 * Filing calendar entry
 */
export interface FilingCalendarEntry {
  filingId: string;
  filingType: FilingType;
  jurisdiction: string;
  frequency: 'ANNUAL' | 'BIENNIAL' | 'QUARTERLY' | 'ONE_TIME';
  dueDate: string;
  gracePeriodDays: number;
  estimatedFee?: number;
  status: 'PENDING' | 'FILED' | 'OVERDUE' | 'WAIVED';
  lastFiledDate?: string;
  confirmationNumber?: string;
  notes?: string;
}

/**
 * Registered agent record
 */
export interface RegisteredAgentRecord {
  jurisdiction: string;
  agentName: string;
  agentAddress: Address;
  agentPhone?: string;
  agentEmail?: string;
  effectiveDate: string;
  isThirdParty: boolean;
  serviceAgreementRef?: string;
  renewalDate?: string;
}

/**
 * Compliance deficiency
 */
export interface ComplianceDeficiency {
  deficiencyId: string;
  jurisdiction: string;
  type: DeficiencyType;
  description: string;
  noticeDate: string;
  noticeRef?: string;
  cureDeadline: string;
  penaltyAmount?: number;
  status: 'OPEN' | 'IN_PROGRESS' | 'CURED' | 'PENALTY_ASSESSED' | 'ADMINISTRATIVE_ACTION';
  curedDate?: string;
  curativeActions: string[];
}

/**
 * Filing history record
 */
export interface FilingHistoryRecord {
  filingId: string;
  filingType: string;
  jurisdiction: string;
  filedDate: string;
  periodCovered: string;
  confirmationNumber: string;
  feePaid: number;
  filedBy: string;
  documentRef: string;
}

/**
 * Good standing certificate
 */
export interface GoodStandingCertificate {
  certificateId: string;
  jurisdiction: string;
  issuedDate: string;
  validThrough?: string;
  documentRef: string;
  purpose?: string;
}

/**
 * Compliance score
 */
export interface ComplianceScore {
  overallStatus: ComplianceStatus;
  openDeficiencies: number;
  overdueFilings: number;
  upcomingDeadlines30Days: number;
  lastAssessedDate: string;
}

/**
 * Compliance state
 */
export interface ComplianceState {
  complianceId: string;
  entityId: string;
  jurisdiction: {
    state: string;
    country: string;
    foreignQualifications: Array<{
      state: string;
      qualificationDate: string;
      foreignEntityNumber: string;
      status: 'ACTIVE' | 'WITHDRAWN' | 'REVOKED';
    }>;
  };
  filingCalendar: FilingCalendarEntry[];
  registeredAgents: RegisteredAgentRecord[];
  deficiencies: ComplianceDeficiency[];
  filingHistory: FilingHistoryRecord[];
  goodStandingCertificates: GoodStandingCertificate[];
  complianceScore: ComplianceScore;
  createdAt: string;
  updatedAt: string;
}

// =============================================================================
// State Machine Definition Types
// =============================================================================

/**
 * State metadata
 */
export interface StateMetadata {
  displayName: string;
  color: string;
}

/**
 * State definition
 */
export interface StateDefinition {
  description: string;
  metadata: StateMetadata;
  terminal?: boolean;
}

/**
 * Transition guard
 */
export interface TransitionGuard {
  name: string;
  description?: string;
  expression?: string;
  crossMachine?: {
    machine: string;
    instanceRef: string;
    requiredState?: string;
    query?: string;
    condition?: string;
  };
  note?: string;
}

/**
 * Transition effect
 */
export interface TransitionEffect {
  type: string;
  path?: string;
  value?: unknown;
  expression?: string;
  condition?: string;
  then?: TransitionEffect;
  amount?: number | string;
  matchKey?: string;
  matchValue?: string;
  updates?: Record<string, unknown>;
  eventType?: string;
  payload?: Record<string, unknown>;
  [key: string]: unknown;
}

/**
 * Event payload property
 */
export interface EventPayloadProperty {
  type: string;
  required?: boolean;
  description?: string;
  format?: string;
  enum?: string[];
  default?: unknown;
  nullable?: boolean;
  items?: { type: string };
}

/**
 * Transition event
 */
export interface TransitionEvent {
  name: string;
  payload: Record<string, EventPayloadProperty>;
}

/**
 * Transition definition
 */
export interface TransitionDefinition {
  from: string | string[] | null;
  to: string | null;
  description: string;
  event: TransitionEvent;
  guards?: TransitionGuard[];
  effects?: TransitionEffect[];
}

/**
 * Cross-machine reference
 */
export interface CrossMachineRef {
  machine: string;
  description: string;
  foreignKey: string;
  eventTriggers?: Record<string, string>;
}

/**
 * State machine metadata
 */
export interface StateMachineMetadata {
  author: string;
  license: string;
  tags: string[];
  documentation: string;
}

/**
 * Complete state machine definition
 */
export interface StateMachineDefinition {
  $schema: string;
  name: string;
  version: string;
  category: string;
  description: string;
  context: Record<string, unknown>;
  states: Record<string, StateDefinition>;
  initialState: string;
  transitions: Record<string, TransitionDefinition>;
  crossMachineRefs?: Record<string, CrossMachineRef>;
  metadata: StateMachineMetadata;
}

// =============================================================================
// Helper Functions
// =============================================================================

/**
 * Get a corporate governance state machine definition by type
 *
 * @param type - The type of corporate definition to retrieve
 * @returns The state machine definition JSON
 *
 * @example
 * ```ts
 * const entityDef = getCorporateDefinition('corporate-entity');
 * console.log(entityDef.name); // "corporate-entity"
 * ```
 */
export function getCorporateDefinition(type: CorporateDefinitionType): StateMachineDefinition {
  return CORPORATE_DEFINITIONS[type] as unknown as StateMachineDefinition;
}

/**
 * Get all available corporate definition types
 *
 * @returns Array of all corporate definition type names
 */
export function getCorporateDefinitionTypes(): CorporateDefinitionType[] {
  return Object.keys(CORPORATE_DEFINITIONS) as CorporateDefinitionType[];
}

/**
 * Extract state machine definition components from a raw JSON definition
 *
 * @param json - The raw state machine JSON object
 * @returns Structured state machine definition components
 *
 * @example
 * ```ts
 * const def = extractStateMachineDefinition(myJsonDefinition);
 * console.log(def.states);       // { ACTIVE: {...}, SUSPENDED: {...} }
 * console.log(def.transitions);  // { suspend: {...}, reinstate: {...} }
 * ```
 */
export function extractStateMachineDefinition(json: unknown): {
  name: string;
  version: string;
  category: string;
  description: string;
  states: Record<string, StateDefinition>;
  initialState: string;
  transitions: Record<string, TransitionDefinition>;
  crossMachineRefs?: Record<string, CrossMachineRef>;
  metadata: StateMachineMetadata;
} {
  const def = json as StateMachineDefinition;
  return {
    name: def.name,
    version: def.version,
    category: def.category,
    description: def.description,
    states: def.states,
    initialState: def.initialState,
    transitions: def.transitions,
    crossMachineRefs: def.crossMachineRefs,
    metadata: def.metadata,
  };
}

/**
 * Get all states for a corporate state machine type
 *
 * @param type - The corporate definition type
 * @returns Record of state names to state definitions
 */
export function getStatesForType(type: CorporateDefinitionType): Record<string, StateDefinition> {
  const def = getCorporateDefinition(type);
  return def.states;
}

/**
 * Get all transitions for a corporate state machine type
 *
 * @param type - The corporate definition type
 * @returns Record of transition names to transition definitions
 */
export function getTransitionsForType(type: CorporateDefinitionType): Record<string, TransitionDefinition> {
  const def = getCorporateDefinition(type);
  return def.transitions;
}

/**
 * Get the initial state for a corporate state machine type
 *
 * @param type - The corporate definition type
 * @returns The initial state name
 */
export function getInitialState(type: CorporateDefinitionType): string {
  const def = getCorporateDefinition(type);
  return def.initialState;
}

/**
 * Check if a state is terminal (no outgoing transitions)
 *
 * @param type - The corporate definition type
 * @param stateName - The state name to check
 * @returns True if the state is marked as terminal
 */
export function isTerminalState(type: CorporateDefinitionType, stateName: string): boolean {
  const def = getCorporateDefinition(type);
  const state = def.states[stateName];
  return state?.terminal === true;
}

/**
 * Get available transitions from a given state
 *
 * @param type - The corporate definition type
 * @param currentState - The current state name
 * @returns Array of transition names available from the current state
 */
export function getAvailableTransitions(type: CorporateDefinitionType, currentState: string): string[] {
  const def = getCorporateDefinition(type);
  return Object.entries(def.transitions)
    .filter(([_, transition]) => {
      const from = transition.from;
      if (from === null) return false;
      if (Array.isArray(from)) return from.includes(currentState);
      return from === currentState;
    })
    .map(([name]) => name);
}

/**
 * Get cross-machine references for a corporate state machine type
 *
 * @param type - The corporate definition type
 * @returns Record of reference names to cross-machine ref definitions, or undefined if none
 */
export function getCrossMachineRefs(type: CorporateDefinitionType): Record<string, CrossMachineRef> | undefined {
  const def = getCorporateDefinition(type);
  return def.crossMachineRefs;
}

/**
 * Validate that a transition is valid from the current state
 *
 * @param type - The corporate definition type
 * @param currentState - The current state name
 * @param transitionName - The transition name to validate
 * @returns True if the transition is valid from the current state
 */
export function isValidTransition(
  type: CorporateDefinitionType,
  currentState: string,
  transitionName: string
): boolean {
  const def = getCorporateDefinition(type);
  const transition = def.transitions[transitionName];
  if (!transition) return false;

  const from = transition.from;
  if (from === null) return true; // Creation transition
  if (Array.isArray(from)) return from.includes(currentState);
  return from === currentState;
}

/**
 * Get the target state for a transition
 *
 * @param type - The corporate definition type
 * @param transitionName - The transition name
 * @returns The target state name, or null for self-transitions
 */
export function getTransitionTarget(type: CorporateDefinitionType, transitionName: string): string | null {
  const def = getCorporateDefinition(type);
  const transition = def.transitions[transitionName];
  return transition?.to ?? null;
}

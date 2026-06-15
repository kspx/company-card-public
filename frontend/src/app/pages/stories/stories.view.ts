import {
    Component, OnInit, ChangeDetectorRef,
    ChangeDetectionStrategy, inject, DestroyRef
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '@app/core/services/auth.service';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTabsModule } from '@angular/material/tabs';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { PublicNavComponent } from '@app/public-nav/public-nav.component';
import { AnonPipe } from '@app/core/pipes/anon.pipe';

export interface TopContributorRow {
    personUri: string; personName: string;
    totalCommits: number; totalLinesAdded: number;
    totalMergedPrs: number; totalReviews: number; projectCount: number;
}
export interface ClientVersatilityRow {
    personUri: string; personName: string;
    clientCount: number; projectCount: number; clients: string;
}

export interface EmployeeJourneyRow {
    personUri: string; personName: string;
    projectUri: string; projectName: string;
    clientUri: string; clientName: string;
    startDate: string | null; endDate: string | null;
    commitCount: number;
}

export interface JourneySegment {
    projectName: string; clientName: string; clientUri: string;
    startDate: string; endDate: string;
    leftPct: number; widthPct: number; color: string; commitCount: number;
}

export interface JourneyTrack {
    personUri: string; personName: string;
    clientCount: number; projectCount: number;
    firstDate: string; lastDate: string;
    segments: JourneySegment[];
}

export interface DeliveryRiskRow {
    orgUri: string; clientName: string; discipline: string;
    personUri: string; personName: string;
    commitCount: number;
}

export interface DisciplineRisk {
    discipline: string;
    contributorCount: number; totalCommits: number;
    topName: string; topPersonUri: string; topShare: number;
    level: 'high' | 'mid' | 'low';
}

export interface DeliveryRiskGroup {
    orgUri: string; clientName: string;
    disciplines: DisciplineRisk[];
}

export interface MeetTheTeamRow {
    orgUri: string; clientName: string;
    personUri: string; personName: string;
    commitCount: number; reviewCount: number; projectCount: number;
    firstActivity: string | null; lastActivity: string | null;
}

export interface MeetTheTeamGroup {
    orgUri: string; clientName: string;
    members: MeetTheTeamRow[];
}

interface StoryState<T> {
    rows: T[]; filtered: T[];
    loading: boolean; error: string | null; loaded: boolean; filter: string;
}

function initState<T>(): StoryState<T> {
    return { rows: [], filtered: [], loading: false, error: null, loaded: false, filter: '' };
}

export interface Signal { label: string; css: string }

export interface RankedBar<T> {
    row: T; rank: number; pct: number;
}

@Component({
    selector: 'app-stories',
    templateUrl: './stories.view.html',
    styleUrls: ['./stories.view.scss'],
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        CommonModule, FormsModule,
        MatButtonModule, MatIconModule, MatProgressSpinnerModule,
        MatTooltipModule, MatTabsModule, MatFormFieldModule, MatInputModule,
        MatSlideToggleModule, PublicNavComponent, AnonPipe,
    ],
})
export class StoriesView implements OnInit {
    private http       = inject(HttpClient);
    private router     = inject(Router);
    private cdr        = inject(ChangeDetectorRef);
    private auth       = inject(AuthService);
    private destroyRef = inject(DestroyRef);

    topContributors        = initState<TopContributorRow>();
    crossClientVersatility = initState<ClientVersatilityRow>();
    employeeJourney        = initState<EmployeeJourneyRow>();
    meetTheTeam            = initState<MeetTheTeamRow>();
    deliveryRisk           = initState<DeliveryRiskRow>();

    readonly tabs = [
        { endpoint: 'top-contributors',         state: this.topContributors,        key: (r: any) => r.personName,                                          filterLabel: 'Filter by name'                       },
        { endpoint: 'cross-client-versatility', state: this.crossClientVersatility, key: (r: any) => r.personName + ' ' + r.clients,                        filterLabel: 'Filter by person or client'           },
        { endpoint: 'employee-journey',         state: this.employeeJourney,        key: (r: any) => r.personName + ' ' + r.clientName + ' ' + r.projectName, filterLabel: 'Filter by person, client or project' },
        { endpoint: 'meet-the-team',            state: this.meetTheTeam,            key: (r: any) => r.clientName + ' ' + r.personName,                     filterLabel: 'Filter by client or person'           },
        { endpoint: 'delivery-risk',            state: this.deliveryRisk,           key: (r: any) => r.clientName + ' ' + r.personName + ' ' + r.discipline, filterLabel: 'Filter by client, person or team'     },
    ];

    ngOnInit(): void {
        this.loadTab(0);
    }

    loadTab(i: number): void {
        const { endpoint, state, key } = this.tabs[i];
        if (state.loaded) return;
        state.loading = true; state.error = null;
        this.http.get<any[]>(`api/stories/${endpoint}`)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: rows => {
                    state.rows = rows; state.loaded = true; state.loading = false;
                    this.applyFilter(state, key);
                    this.cdr.markForCheck();
                },
                error: err => {
                    state.error = err?.error?.message ?? 'Failed to load.';
                    state.loading = false;
                    this.cdr.markForCheck();
                },
            });
    }

    reload(i: number): void {
        this.tabs[i].state.loaded = false;
        this.loadTab(i);
    }

    onFilter(i: number): void {
        const { state, key } = this.tabs[i];
        this.applyFilter(state, key);
    }

    private applyFilter<T>(state: StoryState<T>, key: (r: T) => string): void {
        const q = state.filter.toLowerCase().trim();
        state.filtered = q ? state.rows.filter(r => key(r).toLowerCase().includes(q)) : [...state.rows];
        this.cdr.markForCheck();
    }

    // ── Top Contributors leaderboard ──────────────────────────────────────────────

    get topContributorsRanked(): RankedBar<TopContributorRow>[] {
        const rows = [...this.topContributors.filtered]
            .sort((a, b) => b.totalCommits - a.totalCommits)
            .slice(0, 20);
        const max = Math.max(1, ...rows.map(r => r.totalCommits));
        return rows.map((row, i) => ({ row, rank: i + 1, pct: (row.totalCommits / max) * 100 }));
    }

    // ── Cross-client Versatility leaderboard ──────────────────────────────────────

    get versatilityRanked(): RankedBar<ClientVersatilityRow>[] {
        const rows = [...this.crossClientVersatility.filtered]
            .sort((a, b) => b.clientCount - a.clientCount || b.projectCount - a.projectCount)
            .slice(0, 20);
        const max = Math.max(1, ...rows.map(r => r.clientCount));
        return rows.map((row, i) => ({ row, rank: i + 1, pct: (row.clientCount / max) * 100 }));
    }

    // ── Employee Journey timeline ─────────────────────────────────────────────────

    private readonly journeyPalette = [
        '#1f77b4', '#ff7f0e', '#2ca02c', '#d62728', '#9467bd',
        '#8c564b', '#e377c2', '#17becf', '#bcbd22', '#7f7f7f',
    ];
    private readonly clientColors = new Map<string, string>();

    private colorFor(clientUri: string): string {
        if (!this.clientColors.has(clientUri)) {
            this.clientColors.set(clientUri, this.journeyPalette[this.clientColors.size % this.journeyPalette.length]);
        }
        return this.clientColors.get(clientUri)!;
    }

    private toTime(date: string | null): number | null {
        if (!date) return null;
        const t = Date.parse(date);
        return isNaN(t) ? null : t;
    }

    private journeyBounds(): { min: number; max: number; range: number } | null {
        const times = this.employeeJourney.filtered
            .flatMap(r => [this.toTime(r.startDate), this.toTime(r.endDate ?? r.startDate)])
            .filter((t): t is number => t !== null);
        if (times.length === 0) return null;
        const min = Math.min(...times);
        const max = Math.max(...times);
        return { min, max, range: Math.max(1, max - min) };
    }

    get journeyTracks(): JourneyTrack[] {
        const bounds = this.journeyBounds();
        if (!bounds) return [];
        const { min, range } = bounds;

        const map = new Map<string, JourneyTrack>();
        for (const r of this.employeeJourney.filtered) {
            const start = this.toTime(r.startDate);
            if (start === null) continue;
            const end = Math.max(start, this.toTime(r.endDate ?? r.startDate) ?? start);
            const leftPct = ((start - min) / range) * 100;
            const widthPct = Math.max(1.5, ((end - start) / range) * 100);

            if (!map.has(r.personUri)) {
                map.set(r.personUri, {
                    personUri: r.personUri, personName: r.personName,
                    clientCount: 0, projectCount: 0,
                    firstDate: r.startDate!, lastDate: r.endDate ?? r.startDate!,
                    segments: [],
                });
            }
            map.get(r.personUri)!.segments.push({
                projectName: r.projectName, clientName: r.clientName, clientUri: r.clientUri,
                startDate: r.startDate!, endDate: r.endDate ?? r.startDate!,
                leftPct, widthPct, color: this.colorFor(r.clientUri), commitCount: r.commitCount,
            });
        }

        const tracks = [...map.values()];
        for (const t of tracks) {
            t.segments.sort((a, b) => (this.toTime(a.startDate) ?? 0) - (this.toTime(b.startDate) ?? 0));
            t.clientCount = new Set(t.segments.map(s => s.clientUri)).size;
            t.projectCount = t.segments.length;
            t.firstDate = t.segments[0].startDate;
            t.lastDate = t.segments.reduce(
                (mx, s) => (this.toTime(s.endDate) ?? 0) > (this.toTime(mx) ?? 0) ? s.endDate : mx,
                t.segments[0].endDate,
            );
        }
        return tracks.sort((a, b) => (this.toTime(a.firstDate) ?? 0) - (this.toTime(b.firstDate) ?? 0));
    }

    get journeyClients(): { name: string; color: string }[] {
        const seen = new Map<string, string>();
        for (const r of this.employeeJourney.filtered) {
            if (!seen.has(r.clientUri)) seen.set(r.clientUri, r.clientName);
        }
        return [...seen.entries()]
            .map(([uri, name]) => ({ name, color: this.colorFor(uri) }))
            .sort((a, b) => a.name.localeCompare(b.name));
    }

    get journeyAxis(): { label: string; leftPct: number }[] {
        const bounds = this.journeyBounds();
        if (!bounds) return [];
        const { min, max, range } = bounds;
        const ticks: { label: string; leftPct: number }[] = [];
        for (let y = new Date(min).getFullYear(); y <= new Date(max).getFullYear(); y++) {
            const leftPct = ((new Date(y, 0, 1).getTime() - min) / range) * 100;
            if (leftPct >= -2 && leftPct <= 102) {
                ticks.push({ label: String(y), leftPct: Math.max(0, Math.min(100, leftPct)) });
            }
        }
        return ticks;
    }

    // ── Delivery Risk (bus factor per client) ─────────────────────────────────────

    private riskLevel(contributorCount: number, topShare: number): 'high' | 'mid' | 'low' {
        if (contributorCount === 1 || topShare >= 0.8) return 'high';
        if (topShare >= 0.6) return 'mid';
        return 'low';
    }

    get deliveryRiskGroups(): DeliveryRiskGroup[] {
        const byClient = new Map<string, Map<string, DeliveryRiskRow[]>>();
        for (const r of this.deliveryRisk.filtered) {
            if (!byClient.has(r.orgUri)) byClient.set(r.orgUri, new Map());
            const disc = byClient.get(r.orgUri)!;
            if (!disc.has(r.discipline)) disc.set(r.discipline, []);
            disc.get(r.discipline)!.push(r);
        }
        const groups: DeliveryRiskGroup[] = [];
        for (const [orgUri, discMap] of byClient) {
            let clientName = '?';
            const disciplines: DisciplineRisk[] = [];
            for (const [discipline, members] of discMap) {
                members.sort((a, b) => b.commitCount - a.commitCount);
                clientName = members[0].clientName;
                const committers = members.filter(m => m.commitCount > 0);
                if (committers.length === 0) continue;
                const totalCommits = committers.reduce((s, m) => s + m.commitCount, 0);
                const top = committers[0];
                const contributorCount = committers.length;
                const topShare = top.commitCount / totalCommits;
                disciplines.push({
                    discipline, contributorCount, totalCommits,
                    topName: top.personName, topPersonUri: top.personUri, topShare,
                    level: this.riskLevel(contributorCount, topShare),
                });
            }
            if (disciplines.length === 0) continue;
            disciplines.sort((a, b) => a.discipline.localeCompare(b.discipline));
            groups.push({ orgUri, clientName, disciplines });
        }
        const sev = { high: 2, mid: 1, low: 0 };
        const worst = (g: DeliveryRiskGroup) => Math.max(0, ...g.disciplines.map(d => sev[d.level]));
        return groups.sort((a, b) => worst(b) - worst(a) || a.clientName.localeCompare(b.clientName));
    }

    topPct(share: number): number {
        return Math.floor(share * 100);
    }

    riskLabel(d: DisciplineRisk): string {
        if (d.contributorCount === 1) return 'Sole contributor';
        if (d.level === 'high') return 'High concentration';
        if (d.level === 'mid') return 'Concentrated';
        return 'Distributed';
    }

    riskBadge(level: 'high' | 'mid' | 'low'): string {
        return level === 'high' ? 'signal-warn' : level === 'mid' ? 'signal-ok' : 'signal-good';
    }

    get highRiskCount(): number {
        return this.deliveryRiskGroups.reduce((n, g) => n + g.disciplines.filter(d => d.level === 'high').length, 0);
    }

    // ── Meet the Team (grouped by client) ─────────────────────────────────────────

    readonly activeMonths = 12;
    meetTeamActiveOnly = true;

    isActive(lastActivity: string | null): boolean {
        const t = this.toTime(lastActivity);
        if (t === null) return false;
        const cutoff = new Date();
        cutoff.setMonth(cutoff.getMonth() - this.activeMonths);
        return t >= cutoff.getTime();
    }

    toggleMeetTeamActive(): void {
        this.cdr.markForCheck();
    }

    get groupedMeetTheTeam(): MeetTheTeamGroup[] {
        const map = new Map<string, MeetTheTeamGroup>();
        for (const r of this.meetTheTeam.filtered) {
            if (this.meetTeamActiveOnly && !this.isActive(r.lastActivity)) continue;
            if (!map.has(r.orgUri)) map.set(r.orgUri, { orgUri: r.orgUri, clientName: r.clientName, members: [] });
            map.get(r.orgUri)!.members.push(r);
        }
        const groups = [...map.values()];
        groups.forEach(g => g.members.sort((a, b) => b.commitCount - a.commitCount));
        return groups.sort((a, b) => b.members.length - a.members.length || a.clientName.localeCompare(b.clientName));
    }

    get meetTeamHiddenCount(): number {
        if (!this.meetTeamActiveOnly) return 0;
        return this.meetTheTeam.filtered.filter(r => !this.isActive(r.lastActivity)).length;
    }

    teamRole(row: MeetTheTeamRow): Signal {
        if (row.reviewCount > row.commitCount) return { label: 'Reviewer', css: 'signal-neutral' };
        return { label: 'Coder', css: 'signal-good' };
    }

    back(): void {
        void this.router.navigate([this.auth.isAuthenticated ? '/company-card' : '/login']);
    }
}

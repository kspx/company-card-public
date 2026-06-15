import {
    Component, OnInit, OnDestroy, AfterViewInit,
    ElementRef, ViewChild, ChangeDetectorRef, ChangeDetectionStrategy
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import cytoscape, { Core, NodeSingular } from 'cytoscape';
import { PublicNavComponent } from '@app/public-nav/public-nav.component';
import { PseudonymService } from '@app/core/services/pseudonym.service';

const TYPE_COLORS: Record<string, string> = {
    Person:                      '#4A90D9',
    Organization:                '#5BA85A',
    Project:                     '#E8853B',
    Team:                        '#8E6BBF',
    Employment:                  '#778899',
    Engagement:                  '#D97F8A',
    Certification:               '#C9B84E',
    PersonalCertification:       '#C9B84E',
    OrganizationalCertification: '#B8A040',
    CertificationType:           '#E8C050',
    ProjectContribution:         '#6BBFBF',
};
const DEFAULT_COLOR = '#999';

function typeColor(typeUri: string): string {
    const local = typeUri.includes('#') ? typeUri.split('#').pop()! : typeUri.split('/').pop()!;
    return TYPE_COLORS[local] ?? DEFAULT_COLOR;
}

@Component({
    selector: 'app-graph',
    standalone: true,
    imports: [CommonModule, FormsModule, PublicNavComponent],
    templateUrl: './graph.view.html',
    styleUrls: ['./graph.view.scss'],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphView implements OnInit, AfterViewInit, OnDestroy {
    @ViewChild('cy') cyEl!: ElementRef<HTMLDivElement>;

    isLoading = true;
    error: string | null = null;
    nodeCount = 0;
    edgeCount = 0;
    legend: { label: string; color: string }[] = [];

    tooltip = { visible: false, x: 0, y: 0, label: '', typeLabel: '' };
    searchText = '';

    private cy: Core | null = null;
    private graphData: any = null;

    constructor(
        private http: HttpClient,
        private router: Router,
        private cdr: ChangeDetectorRef,
        private pseudo: PseudonymService,
    ) {}

    ngOnInit(): void {
        this.http.get<any>('api/ontology/graph').subscribe({
            next: data => {
                this.graphData = data;
                this.isLoading = false;
                this.cdr.markForCheck();
                setTimeout(() => this.renderGraph(), 0);
            },
            error: () => {
                this.error = 'Failed to load graph data. Is the backend running?';
                this.isLoading = false;
                this.cdr.markForCheck();
            },
        });
    }

    ngAfterViewInit(): void {
        if (this.graphData) this.renderGraph();
    }

    ngOnDestroy(): void {
        this.cy?.destroy();
    }

    private renderGraph(): void {
        if (!this.cyEl || !this.graphData) return;

        const rawNodes: any[] = this.graphData.nodes ?? [];
        const rawEdges: any[] = this.graphData.edges ?? [];

        // --- Degree-based node sizing ---
        const degreeMap: Record<string, number> = {};
        rawNodes.forEach(n => { degreeMap[n.id] = 0; });
        rawEdges.forEach(e => {
            if (degreeMap[e.source] !== undefined) degreeMap[e.source]++;
            if (degreeMap[e.target] !== undefined) degreeMap[e.target]++;
        });
        const maxDeg = Math.max(...Object.values(degreeMap), 1);

        const nodes = rawNodes.map(n => {
            const deg = degreeMap[n.id] || 0;
            const size = Math.round(55 + (deg / maxDeg) * 75); // 55–130 px
            const display = this.pseudo.label(n.id, n.label);
            return {
                data: {
                    id: n.id,
                    label: this.truncateLabel(display),
                    fullLabel: display,
                    type: n.type,
                    typeLabel: n.typeLabel,
                    color: typeColor(n.type),
                    size,
                    fontSize: Math.round(9 + (deg / maxDeg) * 3),
                },
            };
        });

        const nodeIds = new Set<string>(nodes.map(n => n.data.id));

        // --- Deduplicate + drop dangling edges ---
        const edgeSeen = new Set<string>();
        const validEdges = rawEdges.filter(e => {
            if (!nodeIds.has(e.source) || !nodeIds.has(e.target)) return false;
            const key = `${e.source}|${e.target}|${e.label}`;
            if (edgeSeen.has(key)) return false;
            edgeSeen.add(key);
            return true;
        });

        // --- Parallel edge offsets (unbundled-bezier) ---
        const pairGroups: Record<string, any[]> = {};
        validEdges.forEach(e => {
            const key = [e.source, e.target].sort().join('||');
            if (!pairGroups[key]) pairGroups[key] = [];
            pairGroups[key].push(e);
        });

        const edges = validEdges.map((e, i) => {
            const key = [e.source, e.target].sort().join('||');
            const group = pairGroups[key];
            const idx = group.indexOf(e);
            const total = group.length;
            const offset = total > 1 ? Math.round((idx - (total - 1) / 2) * 55) : 0;
            return {
                data: { id: `e${i}`, source: e.source, target: e.target, label: e.label, curveOffset: offset },
            };
        });

        this.nodeCount = nodes.length;
        this.edgeCount = edges.length;
        this.buildLegend(rawNodes);
        this.cdr.markForCheck();

        this.cy = cytoscape({
            container: this.cyEl.nativeElement,
            elements: { nodes, edges },
            style: [
                {
                    selector: 'node',
                    style: {
                        'background-color': 'data(color)',
                        'label': 'data(label)',
                        'color': '#fff',
                        'text-valign': 'center',
                        'text-halign': 'center',
                        'font-size': 'data(fontSize)',
                        'font-weight': 'bold',
                        'text-wrap': 'wrap',
                        'text-max-width': '85px',
                        'width': 'data(size)',
                        'height': 'data(size)',
                        'border-width': 2,
                        'border-color': '#fff',
                        'text-outline-width': 1,
                        'text-outline-color': 'data(color)',
                    } as any,
                },
                {
                    selector: 'node:selected',
                    style: { 'border-width': 4, 'border-color': '#FFD700' } as any,
                },
                {
                    selector: 'node.dimmed',
                    style: { 'opacity': 0.15 } as any,
                },
                {
                    selector: 'node.highlighted',
                    style: { 'border-color': '#FFD700', 'border-width': 4 } as any,
                },
                {
                    selector: 'edge',
                    style: {
                        'width': 1.5,
                        'line-color': '#445',
                        'target-arrow-color': '#445',
                        'target-arrow-shape': 'triangle',
                        'curve-style': 'unbundled-bezier',
                        'control-point-distances': 'data(curveOffset)',
                        'control-point-weights': 0.5,
                        'label': '',
                    } as any,
                },
                {
                    selector: 'edge:selected, edge.hovered',
                    style: {
                        'width': 3,
                        'line-color': '#4A90D9',
                        'target-arrow-color': '#4A90D9',
                        'label': 'data(label)',
                        'font-size': '10px',
                        'color': '#e0e0e0',
                        'text-background-color': '#1a1a2e',
                        'text-background-opacity': 0.9,
                        'text-background-padding': '3px',
                        'text-rotation': 'autorotate',
                    } as any,
                },
                {
                    selector: 'edge.dimmed',
                    style: { 'opacity': 0.08 } as any,
                },
            ],
            layout: {
                name: 'cose',
                animate: true,
                animationDuration: 900,
                nodeRepulsion: () => 12000,
                idealEdgeLength: () => 150,
                gravity: 0.2,
                numIter: 1200,
                fit: true,
                padding: 50,
            } as any,
        });

        // Hover tooltip
        this.cy.on('mouseover', 'node', evt => {
            const node: NodeSingular = evt.target;
            const pos = node.renderedPosition();
            this.tooltip = {
                visible: true,
                x: pos.x + 15,
                y: pos.y - 55,
                label: node.data('fullLabel'),
                typeLabel: node.data('typeLabel'),
            };
            this.cdr.markForCheck();
        });

        this.cy.on('mouseout', 'node', () => {
            this.tooltip = { ...this.tooltip, visible: false };
            this.cdr.markForCheck();
        });

        // Edge hover — show label in tooltip, highlight edge
        this.cy.on('mouseover', 'edge', evt => {
            const edge = evt.target;
            edge.addClass('hovered');
            const mp = edge.renderedMidpoint();
            this.tooltip = {
                visible: true,
                x: mp.x + 12,
                y: mp.y - 35,
                label: edge.data('label'),
                typeLabel: 'relationship',
            };
            this.cdr.markForCheck();
        });

        this.cy.on('mouseout', 'edge', evt => {
            evt.target.removeClass('hovered');
            this.tooltip = { ...this.tooltip, visible: false };
            this.cdr.markForCheck();
        });

        // Click to navigate
        this.cy.on('tap', 'node', evt => {
            const node: NodeSingular = evt.target;
            void this.router.navigate([
                '/company-card',
                encodeURIComponent(node.data('type')),
                encodeURIComponent(node.data('id')),
            ]);
        });
    }

    // ── Search / highlight ──

    onSearch(text: string): void {
        this.searchText = text;
        if (!this.cy) return;
        if (!text.trim()) {
            this.cy.elements().removeClass('dimmed highlighted');
            return;
        }
        const lower = text.toLowerCase();
        this.cy.nodes().forEach(node => {
            const matches =
                node.data('fullLabel')?.toLowerCase().includes(lower) ||
                node.data('typeLabel')?.toLowerCase().includes(lower);
            node.removeClass('dimmed highlighted');
            if (matches) node.addClass('highlighted');
            else node.addClass('dimmed');
        });
        this.cy.edges().addClass('dimmed');
        this.cy.nodes('.highlighted').connectedEdges().removeClass('dimmed');
    }

    // ── Zoom ──

    zoomIn(): void {
        if (!this.cy) return;
        this.cy.zoom({ level: this.cy.zoom() * 1.3, renderedPosition: this.centerPos() });
    }

    zoomOut(): void {
        if (!this.cy) return;
        this.cy.zoom({ level: this.cy.zoom() / 1.3, renderedPosition: this.centerPos() });
    }

    resetView(): void {
        this.cy?.fit(undefined, 50);
    }

    goBack(): void {
        void this.router.navigate(['/company-card']);
    }

    // ── Helpers ──

    private centerPos() {
        return {
            x: this.cyEl.nativeElement.offsetWidth / 2,
            y: this.cyEl.nativeElement.offsetHeight / 2,
        };
    }

    private truncateLabel(label: string): string {
        return label && label.length > 20 ? label.substring(0, 18) + '…' : label;
    }

    private buildLegend(nodes: any[]): void {
        const seen = new Set<string>();
        this.legend = [];
        for (const n of nodes) {
            const key = n.typeLabel ?? n.type ?? 'Unknown';
            if (!seen.has(key)) {
                seen.add(key);
                this.legend.push({ label: key, color: typeColor(n.type ?? '') });
            }
        }
    }
}

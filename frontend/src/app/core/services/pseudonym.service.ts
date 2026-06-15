import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class PseudonymService {
    private static readonly ENABLED_KEY = 'presentationMode';
    private static readonly MAP_KEY = 'pseudonymMap';

    private _enabled = false;
    private readonly map = new Map<string, string>();
    private counter = 0;

    constructor() {
        this._enabled = localStorage.getItem(PseudonymService.ENABLED_KEY) === 'true';
        const saved = localStorage.getItem(PseudonymService.MAP_KEY);
        if (saved) {
            try {
                for (const [uri, id] of Object.entries(JSON.parse(saved) as Record<string, string>)) {
                    this.map.set(uri, id);
                }
                this.counter = this.map.size;
            } catch {
                /* ignore corrupt cache */
            }
        }
    }

    get enabled(): boolean {
        return this._enabled;
    }

    toggle(): void {
        localStorage.setItem(PseudonymService.ENABLED_KEY, String(!this._enabled));
        window.location.reload();
    }

    isPerson(uri: string | null | undefined): boolean {
        return !!uri && uri.includes('/Person/');
    }

    idFor(uri: string): string {
        let id = this.map.get(uri);
        if (!id) {
            id = 'E-' + String(++this.counter).padStart(3, '0');
            this.map.set(uri, id);
            this.persist();
        }
        return id;
    }

    label(uri: string | null | undefined, fallback: string): string {
        return this._enabled && this.isPerson(uri) ? this.idFor(uri!) : fallback;
    }

    private persist(): void {
        localStorage.setItem(PseudonymService.MAP_KEY, JSON.stringify(Object.fromEntries(this.map)));
    }
}
